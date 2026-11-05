# The estate: orders through the inbox, service A and service B

One order goes through the whole estate. Each link can be checked by one step.

1. Send: `sqs send queue/inbox`, message attributes `id` (the order id, use `ord-{{uuid}}`) and `correlation` (`{{correlation}}`),
   body a JSON order, for example `{"order":1001,"customer":"ACME GmbH","items":[{"sku":"A-1","qty":2}],"paid":true}`.
2. Service A reads the inbox and stores the order in the DynamoDB table `table/messages` (key `id`, status `RECEIVED`):
   `dynamodb get table/messages` with the body `{"Key": {"id": {"S": "<id>"}}}`, wait with `# @until $.status.S == RECEIVED within 20s`.
3. The order only moves on when it is published: `POST {{service/a}}/publish/<id>` (http step, header `X-Correlation-Id: {{correlation}}`,
   `# @assert status == 200`). The bus delivers it to service B, which acks it back to A and writes a Postgres row.
4. Acked: the same `dynamodb get` again, `# @until $.status.S == ACKED within 30s`.
5. Service A logs `ACK received id=<id>` when B acked. Read it after the fact with an `op` step: `### op logs.fetch` and the body
   `{"target": "log/service-a", "since": "10m", "filter": "ACK received id=<id>"}`, `# @assert $.lines == 1`.

The id is the same text in every step: put `ord-{{uuid}}` in each place (`{{uuid}}` is one value for the whole run).
