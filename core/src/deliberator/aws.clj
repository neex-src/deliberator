(ns deliberator.aws
  "Every AWS call. Contract: `(invoke! ctx env service op request) -> response-map` always persists
  the serialised request and the raw response bytes through `store/raw-put!` (`:source :aws`), then
  decodes. Anomalies become `{:type :aws/error :category :message :hint}`, the hint derived from the
  environment (endpoint, account), never typed per service. For `:remote` environments a `:mutate`
  op without `:confirm` in `ctx` returns `{:type :confirm-required :token :summary}`; a valid token
  (single use, 10 minutes) lets it through; every remote call appends to
  `sessions/<key>/audit.log`. Secret-bearing responses (`GetSecretValue`, `GetParameter(s)` and
  `GetParametersByPath` with decryption) from `:remote` are persisted with the value fields
  replaced by \"<redacted>\" unless `ctx` has `:persist-secrets true`.")
