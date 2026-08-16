# Bundled embedding assets (Android)

Run `android/scripts/prepare-embedding-model.sh` on a release machine. It writes:

- `multilingual-e5-small.tflite`
- `multilingual-e5-small-tokenizer/unigram.tsv`
- `multilingual-e5-small-tokenizer/specials.json`

These binaries are gitignored. Debug builds may fall back to
`DeterministicHashEmbedding` when assets are absent. Release must ship them;
the app never downloads embedding weights at runtime.
