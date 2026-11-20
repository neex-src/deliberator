# Deliberator

All the code here is written by AI (Claude Code) and has not been reviewed.

Deliberator runs a system of AWS microservices on your own machine, and lets you drive it from
IntelliJ.

- The services run in Podman containers.
- AWS (SQS, DynamoDB, EventBridge, Lambda, S3, Secrets Manager, ...) is faked by Floci, an AWS
  emulator that runs in a container too.
- Your Terraform is applied to Floci, so the queues, tables and parameters are the ones of the
  real account.
- You write *scenarios*: plain text files, one step per block. A step is a shell command, an AWS
  call, an HTTP request or a check. Run them from the editor and the result of each step shows up
  right under it.
- Change the code of a running service and it is reloaded in the container, no restart.

The parts:

- `ide/` the IntelliJ plugin (Kotlin). It starts the daemon by itself; the daemon is inside the
  plugin zip.
- `core/` `deliberatord`, the daemon that does the work (Clojure).
- `cli/` `dlb`, a small command line client for the daemon (babashka).
- `estate/` a small example system to try it on: an SQS queue, service A (Spring Boot, DynamoDB),
  an EventBridge bus, service B (Postgres) and a Lambda.

## What you need

- git, bb, Podman (`brew install podman`, then once `podman machine init && podman machine start`)
- Java 21 or newer (the example estate is Java 21) and the Clojure CLI
- Maven and Terraform, for the example estate
- IntelliJ IDEA 2026.2 or 2026.3
- `act`, only for chapter 14 (a GitHub Actions workflow run locally)

## Try it

    bb tour:ide

opens a sandbox IntelliJ with the tour: 15 short chapters, each one a scenario that explains its
steps and runs them. Right-click in the editor, Deliberator Scenario, Run Scenario.

    bb tour

runs all chapters in a terminal. It is also the integration test.

## Install the plugin in your own IntelliJ

    bb ide:build    # ide/build/distributions/deliberator-0.1.0.zip

Or take the zip from the Releases page. In IntelliJ: Settings, Plugins, the gear icon, Install
Plugin from Disk, pick the zip, restart.

## Other commands

    bb cli:install    # dlb into ~/.local/bin
    bb core:build     # core/target/deliberatord.jar
    bb core:test      # the daemon's tests
    bb check:arch     # the layering rules

## What it does on your machine

- Starts and stops containers with your Podman: Floci, Postgres, the services. Ports are
  published on 127.0.0.1 only. A port that is taken is moved to the next free one.
- Keeps everything it did as text files under `$DELIBERATOR_HOME` (default
  `~/.local/share/deliberator`).
- Talks to Floci on localhost. It talks to a real AWS account only if you create a remote
  environment with a profile. Then the account is checked first, every change asks for a
  confirmation (or is refused when the environment is read only), every call is written to
  `audit.log` and secret values are blanked out before they reach the disk.
- The Generate action (scenario steps from a description) runs `claude -p` from Claude Code. Only
  when you use it; without Claude Code it fails.

How it is built inside: `docs/architecture.md`.
