grammar MxLang;

StringConstant: '"' (~["\t\b\n\r\\] | '\\' [tbnr\\"])* '"';
NotationSingleLine: '//' .*? ('\n'|EOF) -> skip;
NotationMultiline: '/*' .*? '*/' -> skip;

BlankCharacter : [ \r\t\n]+ -> skip;

Class: 'class';
Bool: 'bool';
Int: 'int';
String: 'string';
Null: 'null';
This: 'this';
True: 'true';
False: 'false';
Void: 'void';
If: 'if';
Else: 'else';
For: 'for';
While: 'while';
Return: 'return';
Break: 'break';
Continue: 'continue';
New: 'new';

// keyword: Bool|Int|String|Null|True|False|Void|If|Else|For|While|Return|Break|Continue;

IntConstant: [0-9]+;
Identifier: [a-zA-Z_\u0080-\u{10ffff}][0-9a-zA-Z_\u0080-\u{10ffff}]*;

constant: IntConstant|StringConstant|Null|True|False;

simpleType: Bool|Int|String|Void|Identifier;

brack: '[]';
indexBrack: '[' expression ']';

arrayType: simpleType brack+;

type: simpleType|arrayType;

expression: '('expression')' #Parentheses
    | New simpleType indexBrack* brack* #NewOperator
    | expression '.' Identifier '(' expressionList ')' #MemberFunctionCall
    | expression '.' Identifier #MemberAccess
    | Identifier '(' expressionList ')' #FunctionCall
    | expression '[' expression ']' #IndexAccess
    | expression op=('++'|'--') #SuffixUnaryOperator
    | <assoc=right> op=('++'|'--'|'+'|'-'|'!'|'~') expression #PrefixUnaryOperator
    | expression op=('*'|'/'|'%') expression #BinaryOperator
    | expression op=('+'|'-') expression #BinaryOperator
    | expression op=('<<'|'>>'|'>>>') expression #BinaryOperator
    | expression op=('<'|'>'|'<='|'>=') expression #BinaryOperator
    | expression op=('=='|'!=') expression #BinaryOperator
    | expression op='&' expression #BinaryOperator
    | expression op='^' expression #BinaryOperator
    | expression op='|' expression #BinaryOperator
    | expression op='&&' expression #BinaryOperator
    | expression op='||' expression #BinaryOperator
    | <assoc=right> expression '?' expression':' expression #TernaryOperator
    | <assoc=right> expression op=('='|'+='|'-='|'*='|'/='|'%='|'&='|'^='|'|='|'<<='|'>>='|'>>>=') expression
        #BinaryOperator
    | Identifier #Identifiers
    | constant #Constants;

expressionList: (expression(','expression)*)?;

statement: ';' #EmptyStatement
    | block #BlockStatement
    | expression ';' #ExpressionStatement
    | variableDeclaration #VariableDeclarationStatement
    | Return expression? ';' #ReturnStatement
    | Break ';' #BreakStatement
    | Continue ';' #ContinueStatement
    | If '(' expression ')' statement #IfStatement
    | If '(' expression ')' thenStatement=statement Else elseStatement=statement #IfElseStatement
    | While '(' expression ')' statement #WhileStatement
    | For '(' (initExpression=expression? ';'|initVariableDeclaration=variableDeclaration) condition=expression ';'
        step=expression? ')' statement #ForStatement;

block: '{' statement* '}';

functionDeclaration: type Identifier '(' (parameter (',' parameter)*)? ')' block;

parameter: type Identifier;

classDeclaration: Class Identifier '{' (variableDeclaration|functionDeclaration)* '}';

variableDeclaration: type variable (',' variable)* ';';

variable: Identifier ('=' expression)?;

section: classDeclaration|functionDeclaration|variableDeclaration;

program: section* EOF;
