package com.dz.intellijjsonnet.lang.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import static com.dz.intellijjsonnet.lang.psi.JsonnetTypes.*;
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
NUMBER={DIGIT}+(\.{DIGIT}+)?([eE][+-]?{DIGIT}+)?

IDENTIFIER=[a-zA-Z_][a-zA-Z0-9_]*

DQ_STRING=\"([^\"\\\r\n]|\\.)*\"
SQ_STRING='([^'\\\r\n]|\\.)*'

%%

<YYINITIAL> {
  {WHITE_SPACE}       { return WHITE_SPACE; }
  {LINE_COMMENT}      { return COMMENT; }
  {BLOCK_COMMENT}     { return COMMENT; }

  "local"             { return LOCAL_KW; }
  "import"            { return IMPORT_KW; }
  "importstr"         { return IMPORTSTR_KW; }
  "true"              { return TRUE_KW; }
  "false"             { return FALSE_KW; }
  "null"              { return NULL_KW; }
  "self"              { return SELF_KW; }
  "super"             { return SUPER_KW; }

  "{"                 { return LBRACE; }
  "}"                 { return RBRACE; }
  "["                 { return LBRACK; }
  "]"                 { return RBRACK; }
  "("                 { return LPAREN; }
  ")"                 { return RPAREN; }
  "::"                { return COLONCOLON; }
  ":"                 { return COLON; }
  ";"                 { return SEMI; }
  ","                 { return COMMA; }
  "="                 { return ASSIGN; }
  "."                 { return DOT; }

  {NUMBER}            { return NUMBER; }
  {DQ_STRING}         { return STRING; }
  {SQ_STRING}         { return STRING; }
  {IDENTIFIER}        { return IDENTIFIER; }

  [^]                 { return BAD_CHARACTER; }
}
