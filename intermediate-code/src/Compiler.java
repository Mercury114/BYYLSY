import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Compiler {
    private static final String INPUT_FILE = "testfile.txt";
    private static final String OUTPUT_FILE = "mips.txt";

    public static void main(String[] args) {
        try {
            String source = new String(Files.readAllBytes(Paths.get(INPUT_FILE)), StandardCharsets.UTF_8);
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.scanAll();
            if (!lexer.getErrors().isEmpty()) {
                LexicalError error = lexer.getErrors().get(0);
                throw new CompileException(error.getLine(), error.getMessage());
            }

            Parser parser = new Parser(tokens);
            List<Quad> beforeOptimization = parser.parseProgram();
            boolean optimize = Boolean.parseBoolean(System.getProperty("compiler.optimize", "false"));
            List<Quad> code = optimize ? Optimizer.optimize(beforeOptimization) : beforeOptimization;

            writeIntermediateCodeIfRequested("compiler.ir.before", beforeOptimization);
            writeIntermediateCodeIfRequested("compiler.ir.after", code);
            String assembly = new MipsGenerator(parser.getVariables(), parser.getStrings(),
                    parser.getTemporaryCount()).generate(code);
            Files.write(Paths.get(OUTPUT_FILE), assembly.getBytes(StandardCharsets.UTF_8));
        } catch (CompileException exception) {
            System.err.println("Compile error at line " + exception.getLine() + ": " + exception.getMessage());
            writeEmptyOutput();
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("Unable to read testfile.txt or write output files: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void writeIntermediateCodeIfRequested(String property, List<Quad> code) throws IOException {
        String outputPath = System.getProperty(property);
        if (outputPath == null || outputPath.trim().isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < code.size(); i++) {
            text.append(i).append(": ").append(code.get(i).format()).append('\n');
        }
        Files.write(Paths.get(outputPath), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEmptyOutput() {
        try {
            Files.write(Paths.get(OUTPUT_FILE), new byte[0]);
        } catch (IOException ignored) {
            System.err.println("Unable to create mips.txt");
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
        private final String message;
        private final int line;

        private LexicalError(String message, int line) {
            this.message = message;
            this.line = line;
        }

        public String getMessage() {
            return message;
        }

        public int getLine() {
            return line;
        }
    }

    public static final class CompileException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int line;

        private CompileException(int line, String message) {
            super(message);
            this.line = line;
        }

        public int getLine() {
            return line;
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

        public Lexer(String source) {
            String normalized = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
            this.source = normalized.startsWith("\uFEFF") ? normalized.substring(1) : normalized;
        }

        public List<Token> scanAll() {
            while (!atEnd()) {
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
                    scanString();
                } else if (!scanOperator()) {
                    errors.add(new LexicalError("Invalid character: " + current, line));
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
                        errors.add(new LexicalError("Unterminated comment", commentLine));
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
            add(category, lexeme, lexeme, tokenLine);
        }

        private void scanInteger() {
            int start = position;
            int tokenLine = line;
            while (!atEnd() && isDigit(peek())) {
                advance();
            }
            String lexeme = source.substring(start, position);
            if (lexeme.length() > 1 && lexeme.charAt(0) == '0') {
                errors.add(new LexicalError("Decimal integer constants cannot start with zero", tokenLine));
            }
            try {
                add("INTCON", lexeme, new BigInteger(lexeme), tokenLine);
            } catch (NumberFormatException exception) {
                errors.add(new LexicalError("Invalid integer constant", tokenLine));
            }
        }

        private void scanString() {
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
                    errors.add(new LexicalError("Line break in string literal", tokenLine));
                    break;
                }
                if (current == '\\') {
                    advance();
                    if (atEnd()) {
                        errors.add(new LexicalError("Incomplete escape sequence", line));
                        break;
                    }
                    char escaped = advance();
                    if (escaped == 'n') {
                        value.append('\n');
                    } else {
                        errors.add(new LexicalError("Only \\n is allowed in a format string", line));
                        value.append(escaped);
                    }
                } else if (current == '%') {
                    if (startsWith("%d")) {
                        value.append("%d");
                        advance();
                        advance();
                    } else {
                        errors.add(new LexicalError("Only %d is allowed in a format string", line));
                        value.append(advance());
                    }
                } else {
                    if (!(current == 32 || current == 33 || current >= 40 && current <= 126)) {
                        errors.add(new LexicalError("Invalid character in format string", line));
                    }
                    value.append(advance());
                }
            }
            if (!closed) {
                errors.add(new LexicalError("Unterminated string literal", tokenLine));
            }
            add("STRCON", source.substring(start, position), value.toString(), tokenLine);
        }

        private boolean scanOperator() {
            if (position + 1 < source.length()) {
                String pair = source.substring(position, position + 2);
                String category = TWO_CHARACTER_TOKENS.get(pair);
                if (category != null) {
                    int tokenLine = line;
                    advance();
                    advance();
                    add(category, pair, pair, tokenLine);
                    return true;
                }
            }
            char current = peek();
            if (current == '&' || current == '|') {
                errors.add(new LexicalError("Expected a doubled logical operator", line));
                advance();
                return true;
            }
            String category = ONE_CHARACTER_TOKENS.get(current);
            if (category == null) {
                return false;
            }
            int tokenLine = line;
            advance();
            add(category, String.valueOf(current), String.valueOf(current), tokenLine);
            return true;
        }

        private void add(String category, String lexeme, Object value, int tokenLine) {
            tokens.add(new Token(category, lexeme, value, tokenLine));
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
            return value == '_' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
        }

        private static boolean isIdentifierPart(char value) {
            return isIdentifierStart(value) || isDigit(value);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static Map<String, String> createKeywords() {
            Map<String, String> result = new HashMap<String, String>();
            result.put("const", "CONSTTK");
            result.put("int", "INTTK");
            result.put("void", "VOIDTK");
            result.put("main", "MAINTK");
            result.put("printf", "PRINTFTK");
            result.put("scanf", "SCANFTK");
            result.put("read", "SCANFTK");
            result.put("getint", "GETINTTK");
            result.put("return", "RETURNTK");
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
            result.put('+', "PLUS");
            result.put('-', "MINU");
            result.put('*', "MULT");
            result.put('/', "DIV");
            result.put('%', "MOD");
            result.put('=', "ASSIGN");
            result.put('!', "NOT");
            result.put(';', "SEMICN");
            result.put(',', "COMMA");
            result.put('(', "LPARENT");
            result.put(')', "RPARENT");
            result.put('{', "LBRACE");
            result.put('}', "RBRACE");
            result.put('[', "LBRACK");
            result.put(']', "RBRACK");
            return result;
        }
    }

    private static final class Value {
        private final boolean constant;
        private final BigInteger number;
        private final String operand;

        private Value(boolean constant, BigInteger number, String operand) {
            this.constant = constant;
            this.number = number;
            this.operand = operand;
        }

        private static Value constant(BigInteger number) {
            return new Value(true, number, "#" + number.toString());
        }

        private static Value operand(String operand) {
            return new Value(false, null, operand);
        }
    }

    private static final class Symbol {
        private final String name;
        private final String label;
        private final boolean constant;
        private final BigInteger constantValue;
        private final int line;

        private Symbol(String name, String label, boolean constant, BigInteger constantValue, int line) {
            this.name = name;
            this.label = label;
            this.constant = constant;
            this.constantValue = constantValue;
            this.line = line;
        }
    }

    private static final class StringData {
        private final String label;
        private final String value;

        private StringData(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final class Quad {
        private final String operation;
        private final String argument1;
        private final String argument2;
        private final String result;

        private Quad(String operation, String argument1, String argument2, String result) {
            this.operation = operation;
            this.argument1 = argument1;
            this.argument2 = argument2;
            this.result = result;
        }

        private String format() {
            return "(" + operation + ", " + show(argument1) + ", " + show(argument2) + ", " + show(result) + ")";
        }

        private static String show(String value) {
            return value == null ? "_" : value;
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final List<Quad> code = new ArrayList<Quad>();
        private final List<Symbol> variables = new ArrayList<Symbol>();
        private final List<StringData> strings = new ArrayList<StringData>();
        private final Map<String, String> stringLabels = new LinkedHashMap<String, String>();
        private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<Map<String, Symbol>>();
        private int position;
        private int nextVariable;
        private int nextTemporary;
        private int nextString;
        private boolean constantExpression;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
            scopes.push(new LinkedHashMap<String, Symbol>());
        }

        private List<Quad> parseProgram() throws CompileException {
            while (check("CONSTTK") || check("INTTK") && !isMainHeader()) {
                if (isFunctionHeader()) {
                    fail("Only main and scalar declarations are supported in this assignment");
                }
                parseDeclaration();
            }
            parseMainFunction();
            if (!atEnd()) {
                fail("Unexpected token after main function: " + current().getLexeme());
            }
            if (code.isEmpty() || !"HALT".equals(code.get(code.size() - 1).operation)) {
                emit("HALT", null, null, null);
            }
            return Collections.unmodifiableList(code);
        }

        private List<Symbol> getVariables() {
            return Collections.unmodifiableList(variables);
        }

        private List<StringData> getStrings() {
            return Collections.unmodifiableList(strings);
        }

        private int getTemporaryCount() {
            return nextTemporary;
        }

        private void parseMainFunction() throws CompileException {
            if (match("VOIDTK")) {
                expect("MAINTK");
            } else {
                expect("INTTK");
                expect("MAINTK");
            }
            expect("LPARENT");
            expect("RPARENT");
            parseBlock(true);
        }

        private void parseDeclaration() throws CompileException {
            boolean isConstant = match("CONSTTK");
            expect("INTTK");
            if (isConstant) {
                parseConstDefinition();
                while (match("COMMA")) {
                    parseConstDefinition();
                }
            } else {
                parseVariableDefinition();
                while (match("COMMA")) {
                    parseVariableDefinition();
                }
            }
            expect("SEMICN");
        }

        private void parseConstDefinition() throws CompileException {
            Token identifier = expect("IDENFR");
            ensureNameAvailable(identifier);
            expect("ASSIGN");
            boolean previousMode = constantExpression;
            constantExpression = true;
            Value initialValue;
            try {
                initialValue = parseExpression();
            } finally {
                constantExpression = previousMode;
            }
            if (!initialValue.constant) {
                failAt(identifier, "Constant initializer must be a constant expression");
            }
            Symbol symbol = new Symbol(identifier.getLexeme(), null, true, initialValue.number, identifier.getLine());
            scopes.peek().put(symbol.name, symbol);
        }

        private void parseVariableDefinition() throws CompileException {
            Token identifier = expect("IDENFR");
            Symbol symbol = declareVariable(identifier);
            if (match("ASSIGN")) {
                Value initialValue = parseExpression();
                emit("ASSIGN", initialValue.operand, null, "@" + symbol.label);
            }
        }

        private Symbol declareVariable(Token identifier) throws CompileException {
            ensureNameAvailable(identifier);
            Symbol symbol = new Symbol(identifier.getLexeme(), "v" + nextVariable++, false, null,
                    identifier.getLine());
            scopes.peek().put(symbol.name, symbol);
            variables.add(symbol);
            return symbol;
        }

        private void ensureNameAvailable(Token identifier) throws CompileException {
            if (scopes.peek().containsKey(identifier.getLexeme())) {
                failAt(identifier, "Duplicate declaration of '" + identifier.getLexeme() + "'");
            }
        }

        private void parseBlock(boolean createScope) throws CompileException {
            expect("LBRACE");
            if (createScope) {
                scopes.push(new LinkedHashMap<String, Symbol>());
            }
            try {
                while (!check("RBRACE") && !atEnd()) {
                    if (check("CONSTTK") || check("INTTK")) {
                        parseDeclaration();
                    } else {
                        parseStatement();
                    }
                }
                expect("RBRACE");
            } finally {
                if (createScope) {
                    scopes.pop();
                }
            }
        }

        private void parseStatement() throws CompileException {
            if (match("SEMICN")) {
                return;
            }
            if (check("LBRACE")) {
                parseBlock(true);
                return;
            }
            if (check("PRINTFTK")) {
                parsePrintf();
                return;
            }
            if (check("SCANFTK")) {
                parseScanf();
                return;
            }
            if (match("RETURNTK")) {
                if (!check("SEMICN")) {
                    parseExpression();
                }
                expect("SEMICN");
                emit("HALT", null, null, null);
                return;
            }
            if (check("IDENFR")) {
                parseAssignmentOrExpression();
                return;
            }
            if (check("IFTK") || check("WHILETK")) {
                fail("Conditional and loop statements are outside the required assignment subset");
            }
            if (check("GETINTTK")) {
                fail("getint must appear as the right side of an assignment");
            }
            parseExpression();
            expect("SEMICN");
        }

        private void parseAssignmentOrExpression() throws CompileException {
            int savedPosition = position;
            Token identifier = current();
            int lookahead = position + 1;
            if (lookahead < tokens.size() && "ASSIGN".equals(tokens.get(lookahead).getCategory())) {
                position++;
                Symbol target = requireWritable(identifier);
                expect("ASSIGN");
                if (match("GETINTTK")) {
                    expect("LPARENT");
                    expect("RPARENT");
                    expect("SEMICN");
                    emit("READ", null, null, "@" + target.label);
                } else {
                    Value value = parseExpression();
                    expect("SEMICN");
                    emit("ASSIGN", value.operand, null, "@" + target.label);
                }
                return;
            }
            position = savedPosition;
            parseExpression();
            expect("SEMICN");
        }

        private void parseScanf() throws CompileException {
            expect("SCANFTK");
            expect("LPARENT");
            Symbol target = requireWritable(expect("IDENFR"));
            emit("READ", null, null, "@" + target.label);
            while (match("COMMA")) {
                target = requireWritable(expect("IDENFR"));
                emit("READ", null, null, "@" + target.label);
            }
            expect("RPARENT");
            expect("SEMICN");
        }

        private void parsePrintf() throws CompileException {
            expect("PRINTFTK");
            expect("LPARENT");
            if (check("STRCON")) {
                String format = (String) consume().getValue();
                List<Value> arguments = new ArrayList<Value>();
                while (match("COMMA")) {
                    arguments.add(parseExpression());
                }
                expect("RPARENT");
                expect("SEMICN");
                emitFormattedOutput(format, arguments);
            } else {
                Value value = parseExpression();
                expect("RPARENT");
                expect("SEMICN");
                emit("PRINT_INT", value.operand, null, null);
                emitLineEnding();
            }
        }

        private void emitFormattedOutput(String format, List<Value> arguments) throws CompileException {
            int placeholderCount = 0;
            for (int i = 0; i + 1 < format.length(); i++) {
                if (format.charAt(i) == '%' && format.charAt(i + 1) == 'd') {
                    placeholderCount++;
                    i++;
                }
            }
            if (placeholderCount != arguments.size()) {
                fail("printf format expects " + placeholderCount + " value(s), received " + arguments.size());
            }

            StringBuilder literal = new StringBuilder();
            int argumentIndex = 0;
            for (int i = 0; i < format.length();) {
                if (i + 1 < format.length() && format.charAt(i) == '%' && format.charAt(i + 1) == 'd') {
                    emitString(literal.toString());
                    literal.setLength(0);
                    emit("PRINT_INT", arguments.get(argumentIndex++).operand, null, null);
                    i += 2;
                } else {
                    literal.append(format.charAt(i++));
                }
            }
            emitString(literal.toString());
            if (!format.endsWith("\n")) {
                emitLineEnding();
            }
        }

        private void emitString(String value) {
            if (value.isEmpty()) {
                return;
            }
            String label = stringLabels.get(value);
            if (label == null) {
                label = "s" + nextString++;
                stringLabels.put(value, label);
                strings.add(new StringData(label, value));
            }
            emit("PRINT_STRING", label, null, null);
        }

        private void emitLineEnding() {
            emitString("\n");
        }

        private Value parseExpression() throws CompileException {
            return parseAdditiveExpression();
        }

        private Value parseAdditiveExpression() throws CompileException {
            Value value = parseMultiplicativeExpression();
            while (check("PLUS") || check("MINU")) {
                String operation = consume().getCategory().equals("PLUS") ? "ADD" : "SUB";
                Value right = parseMultiplicativeExpression();
                value = combine(operation, value, right);
            }
            return value;
        }

        private Value parseMultiplicativeExpression() throws CompileException {
            Value value = parseUnaryExpression();
            while (check("MULT") || check("DIV") || check("MOD")) {
                String category = consume().getCategory();
                String operation = "MULT".equals(category) ? "MUL" : "DIV".equals(category) ? "DIV" : "MOD";
                Value right = parseUnaryExpression();
                value = combine(operation, value, right);
            }
            return value;
        }

        private Value parseUnaryExpression() throws CompileException {
            if (check("PLUS") || check("MINU") || check("NOT")) {
                String category = consume().getCategory();
                Value operand = parseUnaryExpression();
                String operation = "PLUS".equals(category) ? "POS" : "MINU".equals(category) ? "NEG" : "NOT";
                return unary(operation, operand);
            }
            return parsePrimaryExpression();
        }

        private Value parsePrimaryExpression() throws CompileException {
            if (match("LPARENT")) {
                Value value = parseExpression();
                expect("RPARENT");
                return value;
            }
            if (check("INTCON")) {
                return Value.constant((BigInteger) consume().getValue());
            }
            Token identifier = expect("IDENFR");
            if (check("LBRACK")) {
                failAt(current(), "Array references are outside the required assignment subset");
            }
            if (check("LPARENT")) {
                failAt(identifier, "Function calls are outside the required assignment subset");
            }
            Symbol symbol = lookup(identifier);
            if (symbol.constant) {
                return Value.constant(symbol.constantValue);
            }
            return Value.operand("@" + symbol.label);
        }

        private Value combine(String operation, Value left, Value right) throws CompileException {
            if (constantExpression) {
                if (!left.constant || !right.constant) {
                    fail("Constant expression refers to a non-constant variable");
                }
                return Value.constant(evaluateConstant(operation, left.number, right.number));
            }
            String temporary = newTemporary();
            emit(operation, left.operand, right.operand, temporary);
            return Value.operand(temporary);
        }

        private Value unary(String operation, Value value) throws CompileException {
            if (constantExpression) {
                if (!value.constant) {
                    fail("Constant expression refers to a non-constant variable");
                }
                if ("POS".equals(operation)) {
                    return Value.constant(value.number);
                }
                if ("NEG".equals(operation)) {
                    return Value.constant(value.number.negate());
                }
                return Value.constant(value.number.signum() == 0 ? BigInteger.ONE : BigInteger.ZERO);
            }
            String temporary = newTemporary();
            emit(operation, value.operand, null, temporary);
            return Value.operand(temporary);
        }

        private BigInteger evaluateConstant(String operation, BigInteger left, BigInteger right)
                throws CompileException {
            try {
                if ("ADD".equals(operation)) {
                    return left.add(right);
                }
                if ("SUB".equals(operation)) {
                    return left.subtract(right);
                }
                if ("MUL".equals(operation)) {
                    return left.multiply(right);
                }
                if ("DIV".equals(operation)) {
                    return left.divide(right);
                }
                return left.remainder(right);
            } catch (ArithmeticException exception) {
                fail("Invalid constant arithmetic: " + exception.getMessage());
                return BigInteger.ZERO;
            }
        }

        private Symbol requireWritable(Token identifier) throws CompileException {
            if (check("LBRACK")) {
                failAt(current(), "Array references are outside the required assignment subset");
            }
            Symbol symbol = lookup(identifier);
            if (symbol.constant) {
                failAt(identifier, "Cannot assign to constant '" + identifier.getLexeme() + "'");
            }
            return symbol;
        }

        private Symbol lookup(Token identifier) throws CompileException {
            Iterator<Map<String, Symbol>> iterator = scopes.iterator();
            while (iterator.hasNext()) {
                Symbol symbol = iterator.next().get(identifier.getLexeme());
                if (symbol != null) {
                    return symbol;
                }
            }
            failAt(identifier, "Undefined identifier '" + identifier.getLexeme() + "'");
            return null;
        }

        private String newTemporary() {
            return "%" + nextTemporary++;
        }

        private void emit(String operation, String argument1, String argument2, String result) {
            code.add(new Quad(operation, argument1, argument2, result));
        }

        private boolean isMainHeader() {
            return (check("INTTK") || check("VOIDTK")) && typeAt(1).equals("MAINTK");
        }

        private boolean isFunctionHeader() {
            return check("INTTK") && typeAt(1).equals("IDENFR") && typeAt(2).equals("LPARENT");
        }

        private boolean match(String category) {
            if (!check(category)) {
                return false;
            }
            position++;
            return true;
        }

        private Token expect(String category) throws CompileException {
            if (!check(category)) {
                fail("Expected " + category + " but found " + currentCategory());
            }
            return tokens.get(position++);
        }

        private boolean check(String category) {
            return currentCategory().equals(category);
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

        private Token consume() {
            return tokens.get(position++);
        }

        private void fail(String message) throws CompileException {
            int errorLine = atEnd() ? (tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).getLine())
                    : current().getLine();
            throw new CompileException(errorLine, message);
        }

        private void failAt(Token token, String message) throws CompileException {
            throw new CompileException(token.getLine(), message);
        }
    }

    private static final class Optimizer {
        private static List<Quad> optimize(List<Quad> input) {
            List<Quad> result = new ArrayList<Quad>();
            for (Quad quad : input) {
                if ("ASSIGN".equals(quad.operation) && quad.argument1 != null
                        && quad.argument1.equals(quad.result)) {
                    continue;
                }
                result.add(quad);
            }
            return result;
        }
    }

    private static final class MipsGenerator {
        private final List<Symbol> variables;
        private final List<StringData> strings;
        private final int temporaryCount;

        private MipsGenerator(List<Symbol> variables, List<StringData> strings, int temporaryCount) {
            this.variables = variables;
            this.strings = strings;
            this.temporaryCount = temporaryCount;
        }

        private String generate(List<Quad> code) {
            List<String> output = new ArrayList<String>();
            emitData(output);
            output.add(".text");
            output.add(".globl main");
            output.add("main:");
            for (Quad quad : code) {
                emitQuad(output, quad);
            }
            return joinLines(output);
        }

        private void emitData(List<String> output) {
            output.add(".data");
            if (!variables.isEmpty() || temporaryCount > 0) {
                output.add(".align 2");
                for (Symbol variable : variables) {
                    output.add(variable.label + ": .word 0");
                }
                for (int i = 0; i < temporaryCount; i++) {
                    output.add("t" + i + ": .word 0");
                }
            }
            for (StringData string : strings) {
                emitByteString(output, string);
            }
        }

        private void emitByteString(List<String> output, StringData string) {
            byte[] bytes = string.value.getBytes(StandardCharsets.UTF_8);
            int byteCount = bytes.length + 1;
            for (int start = 0; start < byteCount; start += 16) {
                int end = Math.min(start + 16, byteCount);
                StringBuilder line = new StringBuilder(start == 0 ? string.label + ": .byte " : "    .byte ");
                for (int i = start; i < end; i++) {
                    if (i > start) {
                        line.append(", ");
                    }
                    line.append(i == bytes.length ? 0 : bytes[i] & 0xff);
                }
                output.add(line.toString());
            }
        }

        private void emitQuad(List<String> output, Quad quad) {
            if ("ASSIGN".equals(quad.operation)) {
                load(output, quad.argument1, "$t0");
                output.add("sw $t0, " + storageLabel(quad.result));
            } else if ("READ".equals(quad.operation)) {
                output.add("li $v0, 5");
                output.add("syscall");
                output.add("sw $v0, " + storageLabel(quad.result));
            } else if ("PRINT_INT".equals(quad.operation)) {
                load(output, quad.argument1, "$a0");
                output.add("li $v0, 1");
                output.add("syscall");
            } else if ("PRINT_STRING".equals(quad.operation)) {
                output.add("la $a0, " + quad.argument1);
                output.add("li $v0, 4");
                output.add("syscall");
            } else if ("HALT".equals(quad.operation)) {
                output.add("li $v0, 10");
                output.add("syscall");
            } else {
                emitArithmetic(output, quad);
            }
        }

        private void emitArithmetic(List<String> output, Quad quad) {
            load(output, quad.argument1, "$t0");
            if ("NEG".equals(quad.operation)) {
                output.add("subu $t2, $zero, $t0");
            } else if ("POS".equals(quad.operation)) {
                output.add("move $t2, $t0");
            } else if ("NOT".equals(quad.operation)) {
                output.add("sltiu $t2, $t0, 1");
            } else {
                load(output, quad.argument2, "$t1");
                if ("ADD".equals(quad.operation)) {
                    output.add("addu $t2, $t0, $t1");
                } else if ("SUB".equals(quad.operation)) {
                    output.add("subu $t2, $t0, $t1");
                } else if ("MUL".equals(quad.operation)) {
                    output.add("mult $t0, $t1");
                    output.add("mflo $t2");
                } else if ("DIV".equals(quad.operation)) {
                    output.add("div $t0, $t1");
                    output.add("mflo $t2");
                } else if ("MOD".equals(quad.operation)) {
                    output.add("div $t0, $t1");
                    output.add("mfhi $t2");
                }
            }
            output.add("sw $t2, " + storageLabel(quad.result));
        }

        private void load(List<String> output, String operand, String register) {
            if (operand.startsWith("#")) {
                BigInteger number = new BigInteger(operand.substring(1));
                output.add("li " + register + ", " + number.intValue());
            } else {
                output.add("lw " + register + ", " + storageLabel(operand));
            }
        }

        private String storageLabel(String operand) {
            if (operand.startsWith("@")) {
                return operand.substring(1);
            }
            if (operand.startsWith("%")) {
                return "t" + operand.substring(1);
            }
            return operand;
        }

        private String joinLines(List<String> lines) {
            StringBuilder result = new StringBuilder();
            for (String line : lines) {
                result.append(line).append('\n');
            }
            return result.toString();
        }
    }
}
