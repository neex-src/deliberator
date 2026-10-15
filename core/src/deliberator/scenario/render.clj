(ns deliberator.scenario.render
  "Text rendering of runs. Contract: `(result! run-dir)` writes `result.txt`, one section per step
  (`### <kind> ... - <status> - <elapsed>`), the response or rows or lines verbatim, check and cross
  lines for asserts, a `captured:` line and a trailer `RUN <status> steps n failed m`.
  `(diff a b) -> unified diff text` after normalising ids, timestamps and `elapsed` through the
  facts' declared types.")
