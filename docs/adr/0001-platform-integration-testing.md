# 0001. Test write paths with `BasePlatformTestCase`
Status: Accepted (was TODO item 1)

## Context
Rename, the formatter's `CodeStyleManager.reformat()`, the stub-index pipeline and inspection quick fixes need real platform
services, which `ParsingTestCase`'s mock project lacks. `BasePlatformTestCase` was believed to hang for 16+ minutes here, so
that code was shipped reviewed but untested.

## Decision
Root-cause the "hang" instead of working around it. It was never the fixture, but a real bug: `LibsonnetFileType.getName()` was
hardcoded to `"Jsonnet"` while `plugin.xml` declares `"Libsonnet"`. `FileTypeManagerImpl` asserts they match; the failure inside
`StubIndexImpl`'s async init wedged a `CompletableFuture` that `tearDown()`'s leak check awaits with no timeout. `jstack` on the
stuck `Test worker` thread found it. Fixed, and `JsonnetPlatformIntegrationTest` now covers every write path above.

## Consequences
Writing those tests exposed five more real, previously silent bugs, all fixed:
- `JsonnetResolver.resolveLocalName` never resolved a function-sugar bind's own parameters (`local f(x) = x + 1;`) — broken
  go-to-definition/rename for the most common idiom since Phase 1.
- `JsonnetStubIndexUtil.isTopLevelExpr` indexed locals nested in function-sugar bodies as top-level (missing `paramList == null`).
- `JsonnetBlock.getIndent()` indented `{`/`[` and double-indented members (member-list wrapper and its children both indented).
- `JsonnetPsiListEditUtil.deleteListMember` left stray whitespace and could throw `PsiInvalidElementAccessException`; now uses
  `ASTDelegatePsiElement.deleteChildRange`.
- `PsiReferenceBase.getRangeInElement()` threw `No ElementManipulator instance registered` from text-based reference search;
  the references now override it.

Rule going forward: write the platform test first, expect it to fail for a real reason, and read the diff rather than assuming
a fixture problem. If it appears to hang again, `jstack` first. Keep service-shaped code thin and put logic in plain objects
tested at both levels. See `AGENTS.md` "Testing".
