# 0013. Typing-time indentation follows `FixIndentation`
Status: Accepted (was TODO item 15)

## Decision
Rewrite `JsonnetBlock` after the port's `FixIndentation` (Alignment + Indent, `getChildAttributes` for incomplete constructs,
context-aware spacing from the unparser's rules), so Enter and typed closers agree with Reformat Code. Pitfalls found (details in
`AGENTS.md` "Block model"): a multi-line aligned member poisons the indent below it; alignment must sit on the composite, not its first
leaf; a comprehension can't be a transparent wrapper; the platform re-indents only `}` and `)` (so `JsonnetTypedHandler` does all
three from the PSI); Enter between `[]`/`()` needs `JsonnetEnterBetweenBracesDelegate`. Grammar change: `objectLiteral`, `arrayLiteral`,
`binaryTail`, `elseBranch`, `moreBind` are pinned so incomplete code keeps its structure.

## Consequences
- Measured by `JsonnetTypingConsistencyTest` (strip each line's indent from canonical text, `adjustLineIndent`, compare): upstream + oracle
  415/435 (95.4%, floor 95%); with go-jsonnet's `cpp-jsonnet` examples 3433/3472 (98.9%). Misses go to `build/typing-consistency.txt`.
- Deliberate residual shapes: the port's "strong indent", UTF-8 byte columns in hanging alignment, a comment before a comma,
  `cpp_formatting_braces3`.
- Not done: `Wrap`-based line breaking (jsonnetfmt keeps the user's breaks, and so do we).
- Scenarios covered by `JsonnetTypingTest`: Enter after `{ a: 1,`, `[1,`, `f(a,`, `local x =`, `if c then`, `else`, `a +`, `a:` etc.
