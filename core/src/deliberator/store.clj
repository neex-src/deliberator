(ns deliberator.store
  "The on-disk store: `$DELIBERATOR_HOME` (default `~/.local/share/deliberator`), workspace paths,
  atomic EDN read/write (`write-edn!` writes a sibling temp file and renames) and the
  content-addressed raw store.

  Contract: `(raw-put! ws bytes meta) -> {:sha :path :bytes n :new? bool}`, `(raw-get ws sha) ->
  bytes`, `(raw-meta ws sha) -> map`. `meta` must carry `:source` (`:aws :http :podman :sql :file`),
  `:op`, `:env`, `:at` and `:content-type`; `raw-put!` adds `:bytes` and `:sha`. `(link! ws sha
  target-path)` hard-links a raw blob into a run step directory, copying across filesystems.")
