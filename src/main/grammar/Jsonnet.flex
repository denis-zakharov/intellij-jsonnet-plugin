package io.github.denis_zakharov.jsonnettanka.lang.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import static io.github.denis_zakharov.jsonnettanka.lang.psi.JsonnetTypes.*;
import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;

%%

%class JsonnetLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

WHITE_SPACE=[ \t\n\r\f]+
LINE_COMMENT="//"[^\r\n]*|"#"[^\r\n]*
BLOCK_COMMENT="/*"([^*]|\*+[^*/])*\*+"/"

DIGIT=[0-9]
// jsonnet >= 0.20 allows `_` between digits (1_000, 1_0.5_0e1_0), never leading/trailing a digit run.
DIGITS={DIGIT}+(_{DIGIT}+)*
NUMBER={DIGITS}(\.{DIGITS})?([eE][+-]?{DIGITS})?

IDENTIFIER=[a-zA-Z_][a-zA-Z0-9_]*

DQ_STRING=\"([^\"\\\r\n]|\\.)*\"
SQ_STRING='([^'\\\r\n]|\\.)*'
VERBATIM_DQ_STRING=@\"([^\"]|\"\")*\"
VERBATIM_SQ_STRING=@'([^']|'')*'

// The "up to" (~) operator matches the shortest string starting with the
// prefix that has the suffix as its ending — exactly the "read until this
// terminator" semantics a text block needs (same trick JFlex's own examples
// use for `/* ... */` comments). Requiring the suffix to start with a newline
// is what pins the closing `|||` to the start of a line, per the Jsonnet spec,
// without it having to be alone on that line (trailing content like `,` is
// left for normal tokenizing, exactly as `|||,` shows up in real Jsonnet).
TEXT_BLOCK="|||"[ \t]*\r?\n ~ (\r?\n[ \t]*"|||")

%%

<YYINITIAL> {
  {WHITE_SPACE}       { return WHITE_SPACE; }
  {LINE_COMMENT}      { return COMMENT; }
  {BLOCK_COMMENT}     { return COMMENT; }

  {TEXT_BLOCK}        { return STRING; }

  "local"             { return LOCAL_KW; }
  "import"            { return IMPORT_KW; }
  "importstr"         { return IMPORTSTR_KW; }
  "importbin"         { return IMPORTBIN_KW; }
  "true"              { return TRUE_KW; }
  "false"             { return FALSE_KW; }
  "null"              { return NULL_KW; }
  "self"              { return SELF_KW; }
  "super"             { return SUPER_KW; }
  "function"          { return FUNCTION_KW; }
  "if"                { return IF_KW; }
  "then"              { return THEN_KW; }
  "else"              { return ELSE_KW; }
  "for"               { return FOR_KW; }
  "in"                { return IN_KW; }
  "error"             { return ERROR_KW; }
  "assert"            { return ASSERT_KW; }

  "{"                 { return LBRACE; }
  "}"                 { return RBRACE; }
  "["                 { return LBRACK; }
  "]"                 { return RBRACK; }
  "("                 { return LPAREN; }
  ")"                 { return RPAREN; }
  ":::"               { return COLONCOLONCOLON; }
  "::"                { return COLONCOLON; }
  ":"                 { return COLON; }
  "+:::"              { return PLUSCOLONCOLONCOLON; }
  "+::"               { return PLUSCOLONCOLON; }
  "+:"                { return PLUSCOLON; }
  ";"                 { return SEMI; }
  ","                 { return COMMA; }
  "="                 { return ASSIGN; }
  "."                 { return DOT; }
  "$"                 { return DOLLAR; }

  "||"                { return OROR; }
  "&&"                { return ANDAND; }
  "=="                { return EQEQ; }
  "!="                { return NEQ; }
  "<="                { return LTE; }
  ">="                { return GTE; }
  "<<"                { return SHL; }
  ">>"                { return SHR; }
  "<"                 { return LT; }
  ">"                 { return GT; }
  "|"                 { return PIPE; }
  "^"                 { return CARET; }
  "&"                 { return AMP; }
  "+"                 { return PLUS; }
  "-"                 { return MINUS; }
  "*"                 { return STAR; }
  "/"                 { return SLASH; }
  "%"                 { return PERCENT; }
  "!"                 { return BANG; }
  "~"                 { return TILDE; }

  {NUMBER}                { return NUMBER; }
  {DQ_STRING}             { return STRING; }
  {SQ_STRING}             { return STRING; }
  {VERBATIM_DQ_STRING}    { return STRING; }
  {VERBATIM_SQ_STRING}    { return STRING; }
  {IDENTIFIER}            { return IDENTIFIER; }

  [^]                 { return BAD_CHARACTER; }
}
