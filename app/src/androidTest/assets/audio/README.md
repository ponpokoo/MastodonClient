Self-generated fixtures, with no external media or network dependency.

- silence.mp3: 192 MPEG-1 Layer III mono frames, 128 kbit/s, 44.1 kHz.
  Each 417-byte frame consists of header FF FB 90 C4 followed by 413 zero bytes
  (empty granules / silence), approximately 5 seconds.
- tone.wav: 5 seconds of 440 Hz sine, mono PCM16, 16 kHz, amplitude 3000.

The device tests require the playback position to advance and playback to end;
displaying a play button or entering the buffering state alone does not pass.
