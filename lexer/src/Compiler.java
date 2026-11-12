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
    private static final String TOKEN_OUTPUT_PROPERTY = "compiler.tokenOutput";

    public static void main(String[] args) {
        try {
            String source = new String(Files.readAllBytes(Paths.get(INPUT_FILE)), StandardCharsets.UTF_8);
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.scanAll();
            boolean printTokens = Boolean.parseBoolean(
                    System.getProperty(TOKEN_OUTPUT_PROPERTY, "true"));
            writeTokens(tokens, printTokens);
        } catch (IOException exception) {
            System.err.println("Unable to read testfile.txt or write output.txt: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void writeTokens(List<Token> tokens, boolean enabled) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(OUTPUT_FILE), StandardCharsets.UTF_8)) {
            if (!enabled) {
                return;
            }
            for (Token token : tokens) {
                writer.write(token.getCategory());
                writer.write(' ');
                writer.write(token.getLexeme());
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
            if (!atEnd() && position + 1 < source.length()) {
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
}
