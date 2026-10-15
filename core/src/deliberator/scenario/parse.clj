(ns deliberator.scenario.parse
  "Scenario text to data. Contract: `(parse text & {:keys [path]}) -> {:header {:env :vars [...]
  :directives [...]} :steps [{:n :kind :verb :target :name :directives [{:name :value :line}] :body
  :line}]}`; errors `{:type :scenario/parse :line :message}`. Header: `# @env`, `# @var`; step
  header `### <kind> [<verb>] [<target>] [<name>]`; `# @body <path>` is relative to the scenario
  file; bodies are the exact bytes between the directives and the next `###` or EOF, final newline
  kept.")
