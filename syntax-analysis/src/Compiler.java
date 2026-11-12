import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Compiler {
    private static final String INPUT_FILE = "testfile.txt";
    private static final String OUTPUT_FILE = "output.txt";
    private static final String SYNTAX_OUTPUT_PROPERTY = "compiler.syntaxOutput";

    public static void main(String[] args) {
        List<String> outputLines = Collections.emptyList();
        try {
            String source = new String(Files.readAllBytes(Paths.get(INPUT_FILE)), StandardCharsets.UTF_8);
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.scanAll();
            for (LexicalError error : lexer.getErrors()) {
                System.err.println("Lexical error at line " + error.getLine() + ": " + error.getMessage());
            }

            Parser parser = new Parser(tokens);
            try {
                outputLines = parser.parse();
            } catch (ParseException exception) {
                ParseError error = exception.getError();
                System.err.println("Syntax error at line " + error.getLine() + ": " + error.getMessage());
                outputLines = parser.getOutputLines();
            }

            boolean outputEnabled = Boolean.parseBoolean(
                    System.getProperty(SYNTAX_OUTPUT_PROPERTY, "true"));
            writeOutput(outputLines, outputEnabled);
        } catch (IOException exception) {
            System.err.println("Unable to read testfile.txt or write output.txt: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void writeOutput(List<String> lines, boolean enabled) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(OUTPUT_FILE), StandardCharsets.UTF_8)) {
            if (!enabled) {
                return;
            }
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    public static final class Token {
        private final String category;
        private final String lexeme;
        private final Object value;
        private final int line;

        private Token(String category, String lexeme, Object value, int line) {
            this.category = category;
            this.lexeme = lexeme;
            this.value = value;
            this.line = line;
        }

        public String getCategory() {
            return category;
        }

        public String getLexeme() {
            return lexeme;
        }

        public Object getValue() {
            return value;
        }

        public int getLine() {
            return line;
        }
    }

    public static final class LexicalError {
        private final String code;
        private final String message;
        private final int line;

        private LexicalError(String code, String message, int line) {
            this.code = code;
            this.message = message;
            this.line = line;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public int getLine() {
            return line;
        }
    }

    public static final class ParseError {
        private final String code;
        private final String message;
        private final int line;

        private ParseError(String code, String message, int line) {
            this.code = code;
            this.message = message;
            this.line = line;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public int getLine() {
            return line;
        }
    }

    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;
        private final ParseError error;

        private ParseException(ParseError error) {
            super(error.getMessage());
            this.error = error;
        }

        public ParseError getError() {
            return error;
        }
    }

    public static final class Lexer {
        private static final Map<String, String> KEYWORDS = createKeywords();
        private static final Map<String, String> TWO_CHARACTER_TOKENS = createTwoCharacterTokens();
        private static final Map<Character, String> ONE_CHARACTER_TOKENS = createOneCharacterTokens();

        private final String source;
        private final List<Token> tokens = new ArrayList<Token>();
        private final List<LexicalError> errors = new ArrayList<LexicalError>();
        private int position;
        private int line = 1;
        private boolean scanned;

        public Lexer(String source) {
            this.source = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        }

        public List<Token> scanAll() {
            if (scanned) {
                return Collections.unmodifiableList(tokens);
            }
            scanned = true;

            while (true) {
                skipWhitespaceAndComments();
                if (atEnd()) {
                    break;
                }

                char current = peek();
                if (isIdentifierStart(current)) {
                    scanIdentifier();
                } else if (isDigit(current)) {
                    scanInteger();
                } else if (current == '"') {
                    scanFormatString();
                } else if (!scanOperatorOrPunctuation()) {
                    addError("INVALID_CHARACTER", "Invalid character: " + current, line);
                    advance();
                }
            }
            return Collections.unmodifiableList(tokens);
        }

        public List<LexicalError> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        private void skipWhitespaceAndComments() {
            boolean skipped;
            do {
                skipped = false;
                while (!atEnd() && Character.isWhitespace(peek())) {
                    advance();
                    skipped = true;
                }

                if (startsWith("//")) {
                    while (!atEnd() && peek() != '\n') {
                        advance();
                    }
                    skipped = true;
                } else if (startsWith("/*")) {
                    int commentLine = line;
                    advance();
                    advance();
                    boolean closed = false;
                    while (!atEnd()) {
                        if (startsWith("*/")) {
                            advance();
                            advance();
                            closed = true;
                            break;
                        }
                        advance();
                    }
                    if (!closed) {
                        addError("UNTERMINATED_COMMENT", "Unterminated block comment", commentLine);
                    }
                    skipped = true;
                }
            } while (skipped);
        }

        private void scanIdentifier() {
            int start = position;
            int tokenLine = line;
            advance();
            while (!atEnd() && isIdentifierPart(peek())) {
                advance();
            }
            String lexeme = source.substring(start, position);
            String category = KEYWORDS.containsKey(lexeme) ? KEYWORDS.get(lexeme) : "IDENFR";
            addToken(category, lexeme, lexeme, tokenLine);
        }

        private void scanInteger() {
            int start = position;
            int tokenLine = line;
            while (!atEnd() && isDigit(peek())) {
                advance();
            }
            String lexeme = source.substring(start, position);
            if (lexeme.length() > 1 && lexeme.charAt(0) == '0') {
                addError("LEADING_ZERO", "Decimal integer constants cannot start with zero", tokenLine);
            }
            addToken("INTCON", lexeme, new BigInteger(lexeme), tokenLine);
        }

        private void scanFormatString() {
            int start = position;
            int tokenLine = line;
            StringBuilder value = new StringBuilder();
            advance();
            boolean closed = false;

            while (!atEnd()) {
                char current = peek();
                if (current == '"') {
                    advance();
                    closed = true;
                    break;
                }
                if (current == '\n') {
                    addError("UNTERMINATED_STRING", "Line break in format string", tokenLine);
                    break;
                }
                if (current == '\\') {
                    if (startsWith("\\n")) {
                        value.append("\\n");
                        advance();
                        advance();
                    } else {
                        addError("INVALID_STRING_ESCAPE", "Only \\n is valid in a format string", line);
                        value.append(advance());
                        if (!atEnd()) {
                            value.append(advance());
                        }
                    }
                    continue;
                }
                if (current == '%') {
                    if (startsWith("%d")) {
                        value.append("%d");
                        advance();
                        advance();
                    } else {
                        addError("INVALID_FORMAT_SPECIFIER", "Only %d is valid in a format string", line);
                        value.append(advance());
                    }
                    continue;
                }

                if (!isFormatCharacter(current)) {
                    addError("INVALID_FORMAT_CHARACTER", "Invalid character in format string", line);
                }
                value.append(advance());
            }

            if (!closed) {
                addError("UNTERMINATED_STRING", "Unterminated format string", tokenLine);
            }
            addToken("STRCON", source.substring(start, position), value.toString(), tokenLine);
        }

        private boolean scanOperatorOrPunctuation() {
            if (position + 1 < source.length()) {
                String pair = source.substring(position, position + 2);
                String category = TWO_CHARACTER_TOKENS.get(pair);
                if (category != null) {
                    int tokenLine = line;
                    advance();
                    advance();
                    addToken(category, pair, pair, tokenLine);
                    return true;
                }
            }

            char current = peek();
            if (current == '&' || current == '|') {
                addError("INVALID_OPERATOR", "Expected a doubled logical operator", line);
                advance();
                return true;
            }

            String category = ONE_CHARACTER_TOKENS.get(current);
            if (category == null) {
                return false;
            }
            int tokenLine = line;
            advance();
            addToken(category, String.valueOf(current), String.valueOf(current), tokenLine);
            return true;
        }

        private void addToken(String category, String lexeme, Object value, int tokenLine) {
            tokens.add(new Token(category, lexeme, value, tokenLine));
        }

        private void addError(String code, String message, int errorLine) {
            errors.add(new LexicalError(code, message, errorLine));
        }

        private boolean startsWith(String text) {
            return source.startsWith(text, position);
        }

        private boolean atEnd() {
            return position >= source.length();
        }

        private char peek() {
            return source.charAt(position);
        }

        private char advance() {
            char current = source.charAt(position++);
            if (current == '\n') {
                line++;
            }
            return current;
        }

        private static boolean isIdentifierStart(char value) {
            return value == '_' || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
        }

        private static boolean isIdentifierPart(char value) {
            return isIdentifierStart(value) || isDigit(value);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isFormatCharacter(char value) {
            return value == 32 || value == 33 || (value >= 40 && value <= 126 && value != '%' && value != '\\');
        }

        private static Map<String, String> createKeywords() {
            Map<String, String> result = new HashMap<String, String>();
            result.put("main", "MAINTK");
            result.put("const", "CONSTTK");
            result.put("int", "INTTK");
            result.put("break", "BREAKTK");
            result.put("continue", "CONTINUETK");
            result.put("if", "IFTK");
            result.put("else", "ELSETK");
            result.put("while", "WHILETK");
            result.put("getint", "GETINTTK");
            result.put("printf", "PRINTFTK");
            result.put("return", "RETURNTK");
            result.put("void", "VOIDTK");
            return result;
        }

        private static Map<String, String> createTwoCharacterTokens() {
            Map<String, String> result = new HashMap<String, String>();
            result.put("&&", "AND");
            result.put("||", "OR");
            result.put("<=", "LEQ");
            result.put(">=", "GEQ");
            result.put("==", "EQL");
            result.put("!=", "NEQ");
            return result;
        }

        private static Map<Character, String> createOneCharacterTokens() {
            Map<Character, String> result = new HashMap<Character, String>();
            result.put('!', "NOT");
            result.put('*', "MULT");
            result.put('=', "ASSIGN");
            result.put('/', "DIV");
            result.put(';', "SEMICN");
            result.put('%', "MOD");
            result.put(',', "COMMA");
            result.put('<', "LSS");
            result.put('>', "GRE");
            result.put('(', "LPARENT");
            result.put(')', "RPARENT");
            result.put('[', "LBRACK");
            result.put(']', "RBRACK");
            result.put('{', "LBRACE");
            result.put('}', "RBRACE");
            result.put('+', "PLUS");
            result.put('-', "MINU");
            return result;
        }
    }

    public static final class Parser {
        private final List<Token> tokens;
        private final List<String> outputLines = new ArrayList<String>();
        private final List<ParseError> errors = new ArrayList<ParseError>();
        private int position;

        public Parser(List<Token> tokens) {
            this.tokens = tokens == null ? Collections.<Token>emptyList() : tokens;
        }

        public List<String> parse() throws ParseException {
            parseCompUnit();
            if (!atEnd()) {
                fail("UNEXPECTED_TOKEN", "Unexpected token after CompUnit: " + current().getLexeme());
            }
            return getOutputLines();
        }

        public List<String> getOutputLines() {
            return Collections.unmodifiableList(outputLines);
        }

        public List<ParseError> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        private void parseCompUnit() throws ParseException {
            while (check("CONSTTK") || (check("INTTK") && !isMainHeader() && !isFunctionHeader())) {
                parseDecl();
            }
            while (!isMainHeader() && (check("INTTK") || check("VOIDTK"))) {
                parseFuncDef();
            }
            parseMainFuncDef();
            mark("<CompUnit>");
        }

        private void parseDecl() throws ParseException {
            if (check("CONSTTK")) {
                parseConstDecl();
            } else {
                parseVarDecl();
            }
        }

        private void parseConstDecl() throws ParseException {
            expect("CONSTTK");
            parseBType();
            parseConstDef();
            while (match("COMMA")) {
                parseConstDef();
            }
            expect("SEMICN");
            mark("<ConstDecl>");
        }

        private void parseBType() throws ParseException {
            expect("INTTK");
        }

        private void parseConstDef() throws ParseException {
            expect("IDENFR");
            while (match("LBRACK")) {
                parseConstExp();
                expect("RBRACK");
            }
            expect("ASSIGN");
            parseConstInitVal();
            mark("<ConstDef>");
        }

        private void parseConstInitVal() throws ParseException {
            if (match("LBRACE")) {
                if (!check("RBRACE")) {
                    parseConstInitVal();
                    while (match("COMMA")) {
                        parseConstInitVal();
                    }
                }
                expect("RBRACE");
            } else {
                parseConstExp();
            }
            mark("<ConstInitVal>");
        }

        private void parseVarDecl() throws ParseException {
            parseBType();
            parseVarDef();
            while (match("COMMA")) {
                parseVarDef();
            }
            expect("SEMICN");
            mark("<VarDecl>");
        }

        private void parseVarDef() throws ParseException {
            expect("IDENFR");
            while (match("LBRACK")) {
                parseConstExp();
                expect("RBRACK");
            }
            if (match("ASSIGN")) {
                parseInitVal();
            }
            mark("<VarDef>");
        }

        private void parseInitVal() throws ParseException {
            if (match("LBRACE")) {
                if (!check("RBRACE")) {
                    parseInitVal();
                    while (match("COMMA")) {
                        parseInitVal();
                    }
                }
                expect("RBRACE");
            } else {
                parseExp();
            }
            mark("<InitVal>");
        }

        private void parseFuncDef() throws ParseException {
            parseFuncType();
            expect("IDENFR");
            expect("LPARENT");
            if (!check("RPARENT")) {
                parseFuncFParams();
            }
            expect("RPARENT");
            parseBlock();
            mark("<FuncDef>");
        }

        private void parseMainFuncDef() throws ParseException {
            expect("INTTK");
            expect("MAINTK");
            expect("LPARENT");
            expect("RPARENT");
            parseBlock();
            mark("<MainFuncDef>");
        }

        private void parseFuncType() throws ParseException {
            if (check("VOIDTK")) {
                consume();
            } else {
                expect("INTTK");
            }
            mark("<FuncType>");
        }

        private void parseFuncFParams() throws ParseException {
            parseFuncFParam();
            while (match("COMMA")) {
                parseFuncFParam();
            }
            mark("<FuncFParams>");
        }

        private void parseFuncFParam() throws ParseException {
            parseBType();
            expect("IDENFR");
            if (match("LBRACK")) {
                expect("RBRACK");
                while (match("LBRACK")) {
                    parseConstExp();
                    expect("RBRACK");
                }
            }
            mark("<FuncFParam>");
        }

        private void parseBlock() throws ParseException {
            expect("LBRACE");
            while (!check("RBRACE") && !atEnd()) {
                parseBlockItem();
            }
            expect("RBRACE");
            mark("<Block>");
        }

        private void parseBlockItem() throws ParseException {
            if (check("CONSTTK") || check("INTTK")) {
                parseDecl();
            } else {
                parseStmt();
            }
        }

        private void parseStmt() throws ParseException {
            if (check("LBRACE")) {
                parseBlock();
            } else if (match("IFTK")) {
                expect("LPARENT");
                parseCond();
                expect("RPARENT");
                parseStmt();
                if (match("ELSETK")) {
                    parseStmt();
                }
            } else if (match("WHILETK")) {
                expect("LPARENT");
                parseCond();
                expect("RPARENT");
                parseStmt();
            } else if (match("BREAKTK") || match("CONTINUETK")) {
                expect("SEMICN");
            } else if (match("RETURNTK")) {
                if (!check("SEMICN")) {
                    parseExp();
                }
                expect("SEMICN");
            } else if (match("PRINTFTK")) {
                expect("LPARENT");
                expect("STRCON");
                while (match("COMMA")) {
                    parseExp();
                }
                expect("RPARENT");
                expect("SEMICN");
            } else if (hasTopLevelAssignment()) {
                parseLVal();
                expect("ASSIGN");
                if (match("GETINTTK")) {
                    expect("LPARENT");
                    expect("RPARENT");
                } else {
                    parseExp();
                }
                expect("SEMICN");
            } else {
                if (!check("SEMICN")) {
                    parseExp();
                }
                expect("SEMICN");
            }
            mark("<Stmt>");
        }

        private boolean hasTopLevelAssignment() {
            int parentheses = 0;
            int brackets = 0;
            for (int i = position; i < tokens.size(); i++) {
                String category = tokens.get(i).getCategory();
                if (category.equals("SEMICN") && parentheses == 0 && brackets == 0) {
                    return false;
                }
                if (category.equals("ASSIGN") && parentheses == 0 && brackets == 0) {
                    return true;
                }
                if (category.equals("LPARENT")) {
                    parentheses++;
                } else if (category.equals("RPARENT")) {
                    parentheses--;
                } else if (category.equals("LBRACK")) {
                    brackets++;
                } else if (category.equals("RBRACK")) {
                    brackets--;
                }
            }
            return false;
        }

        private void parseExp() throws ParseException {
            parseAddExp();
            mark("<Exp>");
        }

        private void parseCond() throws ParseException {
            parseLOrExp();
            mark("<Cond>");
        }

        private void parseLVal() throws ParseException {
            expect("IDENFR");
            while (match("LBRACK")) {
                parseExp();
                expect("RBRACK");
            }
            mark("<LVal>");
        }

        private void parsePrimaryExp() throws ParseException {
            if (match("LPARENT")) {
                parseExp();
                expect("RPARENT");
            } else if (check("IDENFR")) {
                parseLVal();
            } else {
                expect("INTCON");
                mark("<Number>");
            }
            mark("<PrimaryExp>");
        }

        private void parseUnaryExp() throws ParseException {
            if (check("PLUS") || check("MINU") || check("NOT")) {
                consume();
                mark("<UnaryOp>");
                parseUnaryExp();
            } else if (check("IDENFR") && typeAt(1).equals("LPARENT")) {
                consume();
                expect("LPARENT");
                if (!check("RPARENT")) {
                    parseFuncRParams();
                }
                expect("RPARENT");
            } else {
                parsePrimaryExp();
            }
            mark("<UnaryExp>");
        }

        private void parseFuncRParams() throws ParseException {
            parseExp();
            while (match("COMMA")) {
                parseExp();
            }
            mark("<FuncRParams>");
        }

        private void parseMulExp() throws ParseException {
            parseUnaryExp();
            mark("<MulExp>");
            while (check("MULT") || check("DIV") || check("MOD")) {
                consume();
                parseUnaryExp();
                mark("<MulExp>");
            }
        }

        private void parseAddExp() throws ParseException {
            parseMulExp();
            mark("<AddExp>");
            while (check("PLUS") || check("MINU")) {
                consume();
                parseMulExp();
                mark("<AddExp>");
            }
        }

        private void parseRelExp() throws ParseException {
            parseAddExp();
            mark("<RelExp>");
            while (check("LSS") || check("GRE") || check("LEQ") || check("GEQ")) {
                consume();
                parseAddExp();
                mark("<RelExp>");
            }
        }

        private void parseEqExp() throws ParseException {
            parseRelExp();
            mark("<EqExp>");
            while (check("EQL") || check("NEQ")) {
                consume();
                parseRelExp();
                mark("<EqExp>");
            }
        }

        private void parseLAndExp() throws ParseException {
            parseEqExp();
            mark("<LAndExp>");
            while (match("AND")) {
                parseEqExp();
                mark("<LAndExp>");
            }
        }

        private void parseLOrExp() throws ParseException {
            parseLAndExp();
            mark("<LOrExp>");
            while (match("OR")) {
                parseLAndExp();
                mark("<LOrExp>");
            }
        }

        private void parseConstExp() throws ParseException {
            parseAddExp();
            mark("<ConstExp>");
        }

        private boolean isMainHeader() {
            return check("INTTK") && typeAt(1).equals("MAINTK");
        }

        private boolean isFunctionHeader() {
            return (check("INTTK") || check("VOIDTK"))
                    && typeAt(1).equals("IDENFR")
                    && typeAt(2).equals("LPARENT");
        }

        private boolean match(String category) throws ParseException {
            if (!check(category)) {
                return false;
            }
            consume();
            return true;
        }

        private Token expect(String category) throws ParseException {
            if (!check(category)) {
                fail("UNEXPECTED_TOKEN", "Expected " + category + " but found " + currentCategory());
            }
            return consume();
        }

        private boolean check(String category) {
            return currentCategory().equals(category);
        }

        private Token consume() throws ParseException {
            if (atEnd()) {
                fail("UNEXPECTED_EOF", "Unexpected end of input");
            }
            Token token = tokens.get(position++);
            outputLines.add(token.getCategory() + " " + token.getLexeme());
            return token;
        }

        private Token current() {
            return tokens.get(position);
        }

        private String currentCategory() {
            return atEnd() ? "EOF" : current().getCategory();
        }

        private String typeAt(int offset) {
            int index = position + offset;
            return index >= tokens.size() ? "EOF" : tokens.get(index).getCategory();
        }

        private boolean atEnd() {
            return position >= tokens.size();
        }

        private void mark(String syntaxName) {
            outputLines.add(syntaxName);
        }

        private void fail(String code, String message) throws ParseException {
            int errorLine = atEnd() ? (tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).getLine())
                    : current().getLine();
            ParseError error = new ParseError(code, message, errorLine);
            errors.add(error);
            throw new ParseException(error);
        }
    }
}
