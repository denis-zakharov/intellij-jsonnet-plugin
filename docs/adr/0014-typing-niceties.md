# 0014. Quote pairing and line-comment continuation
Status: Accepted (was TODO item 22)

## Decision
- `JsonnetQuoteHandler` (for both `Jsonnet` and `Libsonnet`) pairs `'`/`"`, types over the closer, doesn't pair after a word character
  (`it's`), in comments or inside the other quote kind; Backspace on `'|'` removes both. The lexer has no unterminated-string token
  (a lone quote is `BAD_CHARACTER`), so `isOpeningQuote`/`hasNonClosedLiteral` treat that as the opener. Text blocks and `@'..'` aren't paired.
- `JsonnetEnterInLineCommentHandler` continues `//` and `#` comments on Enter. This was not a platform default: the platform's handler needs a
  `CodeDocumentationAwareCommenter`, and line and block comments are one `COMMENT` token, so it would have inserted `//` into `/* */`.

## Consequences
Enter at the end of a comment and in block comments stays the platform's. Not done: `*` continuation inside `/* */`. Tests in `JsonnetTypingTest`.
