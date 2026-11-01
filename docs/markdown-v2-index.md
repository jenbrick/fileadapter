# Plain-English Markdown: original and v2

There is a separate v2 copy of every Markdown file in the repository at the start of this review. The originals are unchanged. Pick either column below to compare the wording.

The v2 copies use more direct explanations while keeping the technical meaning, class/setting names, commands, configuration examples and Mermaid diagrams. Links between guides go to the other v2 guides; each copy also links back to its original.

The January documents still distinguish proposed work from current POC behavior and keep optional scope marked for discussion. Verification results remain historical. In the CSV walkthrough, the explanations also clarify completed-with-errors exit codes, default versus optional response handling, and configured MEDIUM handling so they agree with the existing error guide.

| Original | Plain-English v2 |
| --- | --- |
| [README.md](../README.md) | [README-v2.md](../README-v2.md) |
| [docs/architecture.md](architecture.md) | [architecture-v2.md](architecture-v2.md) |
| [docs/contract-batching.md](contract-batching.md) | [contract-batching-v2.md](contract-batching-v2.md) |
| [docs/csv-debug-walkthrough.md](csv-debug-walkthrough.md) | [csv-debug-walkthrough-v2.md](csv-debug-walkthrough-v2.md) |
| [docs/developer-walkthrough.md](developer-walkthrough.md) | [developer-walkthrough-v2.md](developer-walkthrough-v2.md) |
| [docs/error-handling.md](error-handling.md) | [error-handling-v2.md](error-handling-v2.md) |
| [docs/extensions.md](extensions.md) | [extensions-v2.md](extensions-v2.md) |
| [docs/flexible-mappings.md](flexible-mappings.md) | [flexible-mappings-v2.md](flexible-mappings-v2.md) |
| [docs/formats.md](formats.md) | [formats-v2.md](formats-v2.md) |
| [docs/git-bash-run-guide.md](git-bash-run-guide.md) | [git-bash-run-guide-v2.md](git-bash-run-guide-v2.md) |
| [docs/group-member-lookups.md](group-member-lookups.md) | [group-member-lookups-v2.md](group-member-lookups-v2.md) |
| [docs/january-delivery-plan-detailed.md](january-delivery-plan-detailed.md) | [january-delivery-plan-detailed-v2.md](january-delivery-plan-detailed-v2.md) |
| [docs/january-delivery-plan-overview.md](january-delivery-plan-overview.md) | [january-delivery-plan-overview-v2.md](january-delivery-plan-overview-v2.md) |
| [docs/january-release-diagrams.md](january-release-diagrams.md) | [january-release-diagrams-v2.md](january-release-diagrams-v2.md) |
| [docs/runtime-diagrams.md](runtime-diagrams.md) | [runtime-diagrams-v2.md](runtime-diagrams-v2.md) |
| [docs/verification.md](verification.md) | [verification-v2.md](verification-v2.md) |

To compare locally in Git Bash, substitute any pair of filenames:

```bash
git diff --no-index -- docs/january-delivery-plan-overview.md docs/january-delivery-plan-overview-v2.md
```

Exit code 1 from this comparison means differences were found; that is expected.
