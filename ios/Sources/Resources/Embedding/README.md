# Bundled embedding assets

Run `ios/scripts/prepare-embedding-model.sh` on a release machine. It generates
the ignored `multilingual-e5-small.mlmodelc` and
`multilingual-e5-small-tokenizer/` assets in this directory. Release builds fail
when either asset is absent; the application never downloads them at runtime.
