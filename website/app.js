(() => {
  "use strict";

  const frequencies = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
  const frequencyLabels = ["31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz"];
  const defaults = { bass: 35, punch: 28, vocal: 30, preamp: -6, eq: [1.5, 2.5, 1.5, 0, -0.5, 0, 0.5, 1, 1, 0.5] };
  const $ = (id) => document.getElementById(id);
  const fileInput = $("audioFile");
  const audio = $("audio");
  const dropZone = $("dropZone");
  const status = $("status");
  let selectedFile = null;
  let objectUrl = null;
  let audioContext = null;
  let sourceNode = null;
  let audioGraph = null;
  let progressTimer = null;

  const state = {
    bass: defaults.bass, punch: defaults.punch, vocal: defaults.vocal,
    preamp: defaults.preamp, eq: [...defaults.eq]
  };

  function setStatus(message, type = "") {
    status.textContent = message;
    status.className = "status" + (type ? " " + type : "");
  }

  function formatTime(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60).toString().padStart(2, "0");
    return mins + ":" + secs;
  }

  function createGraph(context, source) {
    const preamp = context.createGain();
    const eq = frequencies.map((frequency) => {
      const filter = context.createBiquadFilter();
      filter.type = "peaking";
      filter.frequency.value = Math.min(frequency, context.sampleRate * 0.45);
      filter.Q.value = 1.05;
      return filter;
    });
    const bass = context.createBiquadFilter();
    bass.type = "lowshelf";
    bass.frequency.value = 48;
    const punch = context.createBiquadFilter();
    punch.type = "peaking";
    punch.frequency.value = 108;
    punch.Q.value = 0.9;
    const vocal = context.createBiquadFilter();
    vocal.type = "peaking";
    vocal.frequency.value = 2800;
    vocal.Q.value = 0.8;
    const limiter = context.createDynamicsCompressor();
    limiter.threshold.value = -4;
    limiter.knee.value = 0;
    limiter.ratio.value = 18;
    limiter.attack.value = 0.003;
    limiter.release.value = 0.09;
    const master = context.createGain();
    master.gain.value = 0.92;

    source.connect(preamp);
    let current = preamp;
    for (const filter of eq) { current.connect(filter); current = filter; }
    current.connect(bass);
    bass.connect(punch);
    punch.connect(vocal);
    vocal.connect(limiter);
    limiter.connect(master);
    master.connect(context.destination);

    return { preamp, eq, bass, punch, vocal, limiter, master };
  }

  function applySettings() {
    if (audioGraph) {
      audioGraph.preamp.gain.setTargetAtTime(Math.pow(10, state.preamp / 20), audioContext.currentTime, 0.015);
      state.eq.forEach((gain, index) => {
        audioGraph.eq[index].gain.setTargetAtTime(gain, audioContext.currentTime, 0.015);
      });
      audioGraph.bass.gain.setTargetAtTime(state.bass * 0.075, audioContext.currentTime, 0.02);
      audioGraph.punch.gain.setTargetAtTime(state.punch * 0.045, audioContext.currentTime, 0.02);
      audioGraph.vocal.gain.setTargetAtTime(state.vocal * 0.032, audioContext.currentTime, 0.02);
    }
    $("bassValue").textContent = state.bass + "%";
    $("punchValue").textContent = state.punch + "%";
    $("vocalValue").textContent = state.vocal + "%";
    $("preampValue").textContent = (state.preamp < 0 ? "−" : "") + Math.abs(state.preamp).toFixed(1).replace(".0", "") + " dB";
    state.eq.forEach((gain, index) => {
      const output = $("eqValue" + index);
      if (output) output.textContent = (gain > 0 ? "+" : "") + gain.toFixed(1) + " dB";
    });
  }

  function ensureAudioGraph() {
    if (!audioContext) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) throw new Error("This browser does not support Web Audio. Try the latest Chrome or Edge.");
      audioContext = new AudioContextClass();
      sourceNode = audioContext.createMediaElementSource(audio);
      audioGraph = createGraph(audioContext, sourceNode);
      applySettings();
    }
  }

  function renderEqualizer() {
    $("eqGrid").innerHTML = "";
    frequencies.forEach((frequency, index) => {
      const band = document.createElement("div");
      band.className = "eq-band";
      const label = document.createElement("label");
      label.htmlFor = "eq" + index;
      label.textContent = frequencyLabels[index];
      const slider = document.createElement("input");
      slider.type = "range";
      slider.id = "eq" + index;
      slider.min = "-12"; slider.max = "12"; slider.step = "0.5";
      slider.value = state.eq[index];
      slider.setAttribute("aria-label", frequencyLabels[index] + " equalizer gain");
      const output = document.createElement("output");
      output.id = "eqValue" + index;
      band.append(label, slider, output);
      $("eqGrid").appendChild(band);
      slider.addEventListener("input", () => {
        state.eq[index] = Number(slider.value);
        applySettings();
      });
    });
  }

  function loadFile(file) {
    if (!file) return;
    if (!file.type.startsWith("audio/") && !/\.(mp3|wav|flac|m4a|aac|ogg|opus)$/i.test(file.name)) {
      setStatus("Please choose an audio file.", "error");
      return;
    }
    audio.pause();
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    selectedFile = file;
    objectUrl = URL.createObjectURL(file);
    audio.src = objectUrl;
    audio.load();
    $("fileName").textContent = file.name;
    $("fileMeta").textContent = (file.size / 1024 / 1024).toFixed(1) + " MB · " + (file.type || "Audio file");
    $("playButton").disabled = false;
    $("stopButton").disabled = false;
    $("exportButton").disabled = false;
    $("seek").disabled = false;
    $("playButton").textContent = "▶";
    $("currentTime").textContent = "0:00";
    $("duration").textContent = "…";
    setStatus("Audio selected. Press play. Your file stays in this browser.");
  }

  async function togglePlayback() {
    if (!selectedFile) return;
    try {
      ensureAudioGraph();
      await audioContext.resume();
      if (audio.paused) {
        await audio.play();
      } else {
        audio.pause();
      }
    } catch (error) {
      setStatus(error.message || "Could not start audio playback.", "error");
    }
  }

  function updateTransport() {
    $("playButton").textContent = audio.paused ? "▶" : "Ⅱ";
    $("playButton").setAttribute("aria-label", audio.paused ? "Play" : "Pause");
    if (Number.isFinite(audio.duration) && audio.duration > 0) {
      $("seek").value = Math.round((audio.currentTime / audio.duration) * 1000);
      $("currentTime").textContent = formatTime(audio.currentTime);
      $("duration").textContent = formatTime(audio.duration);
    }
  }

  function resetSettings() {
    state.bass = defaults.bass; state.punch = defaults.punch; state.vocal = defaults.vocal;
    state.preamp = defaults.preamp; state.eq = [...defaults.eq];
    $("bass").value = state.bass; $("punch").value = state.punch;
    $("vocal").value = state.vocal; $("preamp").value = state.preamp;
    state.eq.forEach((gain, index) => { $("eq" + index).value = gain; });
    applySettings();
  }

  function encodeWav(buffer) {
    const channels = buffer.numberOfChannels;
    const sampleRate = buffer.sampleRate;
    const frames = buffer.length;
    const bytesPerSample = 2;
    const dataBytes = frames * channels * bytesPerSample;
    const out = new ArrayBuffer(44 + dataBytes);
    const view = new DataView(out);
    const writeText = (offset, value) => { for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i)); };
    writeText(0, "RIFF"); view.setUint32(4, 36 + dataBytes, true); writeText(8, "WAVE");
    writeText(12, "fmt "); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
    view.setUint16(22, channels, true); view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * channels * bytesPerSample, true);
    view.setUint16(32, channels * bytesPerSample, true); view.setUint16(34, 16, true);
    writeText(36, "data"); view.setUint32(40, dataBytes, true);
    const channelData = Array.from({ length: channels }, (_, channel) => buffer.getChannelData(channel));
    let offset = 44;
    for (let frame = 0; frame < frames; frame++) {
      for (let channel = 0; channel < channels; channel++) {
        const sample = Math.max(-1, Math.min(1, channelData[channel][frame]));
        const pcm = sample < 0 ? sample * 32768 : sample * 32767;
        view.setInt16(offset, Math.round(pcm), true);
        offset += 2;
      }
    }
    return new Blob([out], { type: "audio/wav" });
  }

  async function exportWav() {
    if (!selectedFile) return;
    const button = $("exportButton");
    button.disabled = true;
    button.textContent = "Rendering…";
    setStatus("Rendering processed WAV in your browser. Keep this tab open…");
    try {
      const bytes = await selectedFile.arrayBuffer();
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      const decoder = new AudioContextClass();
      const decoded = await decoder.decodeAudioData(bytes.slice(0));
      await decoder.close();
      if (decoded.duration > 600) {
        throw new Error("This web exporter is limited to 10-minute files to avoid excessive phone memory use.");
      }
      const OfflineContextClass = window.OfflineAudioContext || window.webkitOfflineAudioContext;
      if (!OfflineContextClass) throw new Error("Offline WAV rendering is not supported by this browser.");
      const offline = new OfflineContextClass(decoded.numberOfChannels, decoded.length, decoded.sampleRate);
      const source = offline.createBufferSource();
      source.buffer = decoded;
      const offlineGraph = createGraph(offline, source);
      offlineGraph.preamp.gain.value = Math.pow(10, state.preamp / 20);
      state.eq.forEach((gain, index) => { offlineGraph.eq[index].gain.value = gain; });
      offlineGraph.bass.gain.value = state.bass * 0.075;
      offlineGraph.punch.gain.value = state.punch * 0.045;
      offlineGraph.vocal.gain.value = state.vocal * 0.032;
      source.start(0);
      const rendered = await offline.startRendering();
      const wav = encodeWav(rendered);
      const downloadUrl = URL.createObjectURL(wav);
      const link = document.createElement("a");
      link.href = downloadUrl;
      link.download = selectedFile.name.replace(/\.[^.]+$/, "") + "_DeepBass.wav";
      document.body.appendChild(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(downloadUrl), 30000);
      setStatus("Processed WAV created and downloaded. Audio was processed locally.", "success");
    } catch (error) {
      setStatus(error.message || "WAV export failed in this browser.", "error");
    } finally {
      button.disabled = !selectedFile;
      button.textContent = "Export WAV ↓";
    }
  }

  $("chooseButton").addEventListener("click", () => fileInput.click());
  dropZone.addEventListener("click", (event) => {
    if (event.target.closest("button")) return;
    fileInput.click();
  });
  dropZone.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); fileInput.click(); }
  });
  fileInput.addEventListener("change", () => loadFile(fileInput.files && fileInput.files[0]));
  ["dragenter", "dragover"].forEach((name) => dropZone.addEventListener(name, (event) => {
    event.preventDefault(); dropZone.classList.add("dragover");
  }));
  ["dragleave", "drop"].forEach((name) => dropZone.addEventListener(name, (event) => {
    event.preventDefault(); dropZone.classList.remove("dragover");
  }));
  dropZone.addEventListener("drop", (event) => loadFile(event.dataTransfer.files && event.dataTransfer.files[0]));
  $("playButton").addEventListener("click", togglePlayback);
  $("stopButton").addEventListener("click", () => { audio.pause(); audio.currentTime = 0; updateTransport(); });
  $("seek").addEventListener("input", () => {
    if (Number.isFinite(audio.duration) && audio.duration > 0) audio.currentTime = (Number($("seek").value) / 1000) * audio.duration;
  });
  $("resetButton").addEventListener("click", resetSettings);
  $("exportButton").addEventListener("click", exportWav);
  ["bass", "punch", "vocal", "preamp"].forEach((name) => {
    $(name).addEventListener("input", () => { state[name] = Number($(name).value); applySettings(); });
  });
  audio.addEventListener("play", updateTransport);
  audio.addEventListener("pause", updateTransport);
  audio.addEventListener("ended", updateTransport);
  audio.addEventListener("loadedmetadata", updateTransport);
  audio.addEventListener("error", () => setStatus("This browser cannot decode the selected format. Try MP3 or WAV, or use a compatible browser.", "error"));
  progressTimer = window.setInterval(updateTransport, 200);
  renderEqualizer();
  applySettings();

  if ("serviceWorker" in navigator && location.protocol.startsWith("http")) {
    window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(() => {}));
  }
  window.addEventListener("beforeunload", () => {
    if (progressTimer) clearInterval(progressTimer);
    if (objectUrl) URL.revokeObjectURL(objectUrl);
  });
})();