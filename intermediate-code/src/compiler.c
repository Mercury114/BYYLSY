#include <ctype.h>
#include <errno.h>
#include <inttypes.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define INPUT_FILE "testfile.txt"
#define OUTPUT_FILE "mips.txt"
#define MAX_SCOPES 128

typedef enum {
    TK_EOF, TK_IDENT, TK_NUMBER, TK_STRING,
    TK_CONST, TK_INT, TK_VOID, TK_MAIN, TK_PRINTF, TK_SCANF, TK_GETINT, TK_RETURN,
    TK_PLUS, TK_MINUS, TK_MULT, TK_DIV, TK_MOD, TK_ASSIGN, TK_NOT,
    TK_AND, TK_OR, TK_LT, TK_GT, TK_LE, TK_GE, TK_EQ, TK_NE,
    TK_SEMICOLON, TK_COMMA, TK_LPAREN, TK_RPAREN, TK_LBRACE, TK_RBRACE,
    TK_LBRACKET, TK_RBRACKET
} TokenType;

typedef struct {
    TokenType type;
    char *text;
    char *string_value;
    int64_t number;
    int line;
} Token;

typedef struct {
    char *data;
    size_t length;
    size_t capacity;
} Buffer;

typedef struct {
    char operation[16];
    char *argument1;
    char *argument2;
    char *result;
} Quad;

typedef struct {
    char name[64];
    char label[32];
    bool is_constant;
    int64_t constant_value;
    int line;
} Symbol;

typedef struct {
    char label[32];
    char *value;
} StringData;

typedef struct {
    bool is_constant;
    int64_t constant_value;
    char operand[80];
} Value;

static jmp_buf compile_error_jump;
static int error_line = 1;
static char error_message[512];

static Token *tokens;
static size_t token_count;
static size_t token_capacity;
static size_t position;
static int lexer_line = 1;
static const char *source_text;
static size_t source_length;
static size_t source_position;

static Quad *quads;
static size_t quad_count;
static size_t quad_capacity;
static Symbol *symbols;
static size_t symbol_count;
static size_t symbol_capacity;
static size_t scope_starts[MAX_SCOPES];
static int scope_depth;
static char (*variable_labels)[32];
static size_t variable_count;
static size_t variable_capacity;
static StringData *strings;
static size_t string_count;
static size_t string_capacity;
static unsigned int next_temporary;
static unsigned int next_string;
static bool constant_expression;

static void fail_at(int line, const char *format, ...) {
    va_list arguments;
    error_line = line > 0 ? line : 1;
    va_start(arguments, format);
    vsnprintf(error_message, sizeof(error_message), format, arguments);
    va_end(arguments);
    longjmp(compile_error_jump, 1);
}

static void fail(const char *format, ...) {
    va_list arguments;
    int line = position < token_count ? tokens[position].line : lexer_line;
    error_line = line > 0 ? line : 1;
    va_start(arguments, format);
    vsnprintf(error_message, sizeof(error_message), format, arguments);
    va_end(arguments);
    longjmp(compile_error_jump, 1);
}

static char *duplicate_range(const char *text, size_t length) {
    char *copy = (char *)malloc(length + 1);
    if (copy == NULL) {
        fail("Out of memory");
    }
    memcpy(copy, text, length);
    copy[length] = '\0';
    return copy;
}

static char *duplicate_string(const char *text) {
    return duplicate_range(text, strlen(text));
}

static void buffer_reserve(Buffer *buffer, size_t additional) {
    size_t required = buffer->length + additional + 1;
    if (required <= buffer->capacity) {
        return;
    }
    size_t capacity = buffer->capacity == 0 ? 128 : buffer->capacity;
    while (capacity < required) {
        capacity *= 2;
    }
    char *data = (char *)realloc(buffer->data, capacity);
    if (data == NULL) {
        fail("Out of memory");
    }
    buffer->data = data;
    buffer->capacity = capacity;
}

static void buffer_append_n(Buffer *buffer, const char *text, size_t length) {
    buffer_reserve(buffer, length);
    memcpy(buffer->data + buffer->length, text, length);
    buffer->length += length;
    buffer->data[buffer->length] = '\0';
}

static void buffer_append(Buffer *buffer, const char *text) {
    buffer_append_n(buffer, text, strlen(text));
}

static void buffer_append_char(Buffer *buffer, char value) {
    buffer_reserve(buffer, 1);
    buffer->data[buffer->length++] = value;
    buffer->data[buffer->length] = '\0';
}

static void buffer_append_format(Buffer *buffer, const char *format, ...) {
    va_list arguments;
    va_list copy;
    va_start(arguments, format);
    va_copy(copy, arguments);
    int needed = vsnprintf(NULL, 0, format, copy);
    va_end(copy);
    if (needed < 0) {
        va_end(arguments);
        fail("Unable to format output");
    }
    buffer_reserve(buffer, (size_t)needed);
    vsnprintf(buffer->data + buffer->length, buffer->capacity - buffer->length, format, arguments);
    buffer->length += (size_t)needed;
    va_end(arguments);
}

static bool source_at_end(void) {
    return source_position >= source_length;
}

static char source_peek(void) {
    return source_text[source_position];
}

static char source_peek_next(void) {
    return source_position + 1 < source_length ? source_text[source_position + 1] : '\0';
}

static char source_advance(void) {
    char value = source_text[source_position++];
    if (value == '\n') {
        lexer_line++;
    }
    return value;
}

static bool identifier_start(char value) {
    return value == '_' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
}

static bool identifier_part(char value) {
    return identifier_start(value) || (value >= '0' && value <= '9');
}

static void append_token(TokenType type, const char *text, size_t length, const char *string_value,
                         int64_t number, int line) {
    if (token_count == token_capacity) {
        size_t new_capacity = token_capacity == 0 ? 128 : token_capacity * 2;
        Token *new_tokens = (Token *)realloc(tokens, new_capacity * sizeof(Token));
        if (new_tokens == NULL) {
            fail_at(line, "Out of memory");
        }
        tokens = new_tokens;
        token_capacity = new_capacity;
    }
    Token *token = &tokens[token_count++];
    token->type = type;
    token->text = duplicate_range(text, length);
    token->string_value = string_value == NULL ? NULL : duplicate_string(string_value);
    token->number = number;
    token->line = line;
}

static TokenType keyword_type(const char *text, size_t length) {
    struct Keyword { const char *text; TokenType type; };
    static const struct Keyword keywords[] = {
        {"const", TK_CONST}, {"int", TK_INT}, {"void", TK_VOID}, {"main", TK_MAIN},
        {"printf", TK_PRINTF}, {"scanf", TK_SCANF}, {"read", TK_SCANF},
        {"getint", TK_GETINT}, {"return", TK_RETURN}
    };
    size_t count = sizeof(keywords) / sizeof(keywords[0]);
    for (size_t i = 0; i < count; i++) {
        if (strlen(keywords[i].text) == length && memcmp(keywords[i].text, text, length) == 0) {
            return keywords[i].type;
        }
    }
    return TK_IDENT;
}

static void skip_space_and_comments(void) {
    bool skipped;
    do {
        skipped = false;
        while (!source_at_end() && isspace((unsigned char)source_peek())) {
            source_advance();
            skipped = true;
        }
        if (source_peek() == '/' && source_peek_next() == '/') {
            while (!source_at_end() && source_peek() != '\n') {
                source_advance();
            }
            skipped = true;
        } else if (source_peek() == '/' && source_peek_next() == '*') {
            int comment_line = lexer_line;
            source_advance();
            source_advance();
            bool closed = false;
            while (!source_at_end()) {
                if (source_peek() == '*' && source_peek_next() == '/') {
                    source_advance();
                    source_advance();
                    closed = true;
                    break;
                }
                source_advance();
            }
            if (!closed) {
                fail_at(comment_line, "Unterminated block comment");
            }
            skipped = true;
        }
    } while (skipped);
}

static void scan_identifier(void) {
    size_t start = source_position;
    int line = lexer_line;
    source_advance();
    while (!source_at_end() && identifier_part(source_peek())) {
        source_advance();
    }
    size_t length = source_position - start;
    TokenType type = keyword_type(source_text + start, length);
    append_token(type, source_text + start, length, NULL, 0, line);
}

static void scan_number(void) {
    size_t start = source_position;
    int line = lexer_line;
    while (!source_at_end() && isdigit((unsigned char)source_peek())) {
        source_advance();
    }
    size_t length = source_position - start;
    if (length > 1 && source_text[start] == '0') {
        fail_at(line, "Decimal integer constants cannot start with zero");
    }
    char *text = duplicate_range(source_text + start, length);
    errno = 0;
    char *end = NULL;
    long long number = strtoll(text, &end, 10);
    if (errno == ERANGE || end == text || *end != '\0') {
        fail_at(line, "Integer constant is out of range");
    }
    append_token(TK_NUMBER, source_text + start, length, NULL, (int64_t)number, line);
}

static void scan_string(void) {
    size_t start = source_position;
    int line = lexer_line;
    Buffer value = {0};
    source_advance();
    bool closed = false;
    while (!source_at_end()) {
        char current = source_peek();
        if (current == '"') {
            source_advance();
            closed = true;
            break;
        }
        if (current == '\n') {
            fail_at(line, "Line break in format string");
        }
        if (current == '\\') {
            source_advance();
            if (source_at_end()) {
                fail_at(line, "Incomplete escape sequence");
            }
            char escaped = source_advance();
            if (escaped != 'n') {
                fail_at(line, "Only \\n is allowed in a format string");
            }
            buffer_append_char(&value, '\n');
        } else if (current == '%') {
            if (source_peek_next() != 'd') {
                fail_at(line, "Only %d is allowed in a format string");
            }
            buffer_append(&value, "%d");
            source_advance();
            source_advance();
        } else {
            unsigned char character = (unsigned char)current;
            if (!(character == 32 || character == 33 || character == 39 ||
                  (character >= 40 && character <= 126))) {
                fail_at(lexer_line, "Invalid character in format string");
            }
            buffer_append_char(&value, source_advance());
        }
    }
    if (!closed) {
        fail_at(line, "Unterminated format string");
    }
    append_token(TK_STRING, source_text + start, source_position - start,
                 value.data == NULL ? "" : value.data, 0, line);
    free(value.data);
}

static void scan_operator_or_punctuation(void) {
    int line = lexer_line;
    char first = source_peek();
    char second = source_peek_next();
    TokenType type = TK_EOF;
    size_t length = 1;
    if ((first == '&' && second == '&') || (first == '|' && second == '|') ||
        (first == '<' && second == '=') || (first == '>' && second == '=') ||
        (first == '=' && second == '=') || (first == '!' && second == '=')) {
        length = 2;
        if (first == '&') type = TK_AND;
        else if (first == '|') type = TK_OR;
        else if (first == '<') type = TK_LE;
        else if (first == '>') type = TK_GE;
        else if (first == '=') type = TK_EQ;
        else type = TK_NE;
    } else {
        switch (first) {
            case '+': type = TK_PLUS; break;
            case '-': type = TK_MINUS; break;
            case '*': type = TK_MULT; break;
            case '/': type = TK_DIV; break;
            case '%': type = TK_MOD; break;
            case '=': type = TK_ASSIGN; break;
            case '!': type = TK_NOT; break;
            case '<': type = TK_LT; break;
            case '>': type = TK_GT; break;
            case ';': type = TK_SEMICOLON; break;
            case ',': type = TK_COMMA; break;
            case '(': type = TK_LPAREN; break;
            case ')': type = TK_RPAREN; break;
            case '{': type = TK_LBRACE; break;
            case '}': type = TK_RBRACE; break;
            case '[': type = TK_LBRACKET; break;
            case ']': type = TK_RBRACKET; break;
            case '&': case '|': fail_at(line, "Expected a doubled logical operator"); break;
            default: fail_at(line, "Invalid character: %c", first);
        }
    }
    source_advance();
    if (length == 2) {
        source_advance();
    }
    append_token(type, source_text + source_position - length, length, NULL, 0, line);
}

static void lex_source(const char *text, size_t length) {
    source_text = text;
    source_length = length;
    source_position = 0;
    lexer_line = 1;
    while (!source_at_end()) {
        skip_space_and_comments();
        if (source_at_end()) {
            break;
        }
        char current = source_peek();
        if (identifier_start(current)) {
            scan_identifier();
        } else if (isdigit((unsigned char)current)) {
            scan_number();
        } else if (current == '"') {
            scan_string();
        } else {
            scan_operator_or_punctuation();
        }
    }
    append_token(TK_EOF, "", 0, NULL, 0, lexer_line);
}

static Token *current_token(void) {
    return &tokens[position < token_count ? position : token_count - 1];
}

static TokenType current_type(void) {
    return current_token()->type;
}

static TokenType type_at(size_t offset) {
    size_t index = position + offset;
    return index < token_count ? tokens[index].type : TK_EOF;
}

static bool match(TokenType type) {
    if (current_type() != type) {
        return false;
    }
    position++;
    return true;
}

static Token *expect(TokenType type) {
    if (current_type() != type) {
        fail("Expected token %d, found '%s'", (int)type, current_token()->text);
    }
    return &tokens[position++];
}

static char *format_integer(int64_t number) {
    char buffer[64];
    snprintf(buffer, sizeof(buffer), "#%" PRId64, number);
    return duplicate_string(buffer);
}

static Value constant_value(int64_t number) {
    Value value;
    value.is_constant = true;
    value.constant_value = number;
    snprintf(value.operand, sizeof(value.operand), "#%" PRId64, number);
    return value;
}

static Value operand_value(const char *operand) {
    Value value;
    value.is_constant = false;
    value.constant_value = 0;
    snprintf(value.operand, sizeof(value.operand), "%s", operand);
    return value;
}

static void emit_quad(const char *operation, const char *argument1, const char *argument2,
                      const char *result) {
    if (quad_count == quad_capacity) {
        size_t new_capacity = quad_capacity == 0 ? 128 : quad_capacity * 2;
        Quad *new_quads = (Quad *)realloc(quads, new_capacity * sizeof(Quad));
        if (new_quads == NULL) {
            fail("Out of memory");
        }
        quads = new_quads;
        quad_capacity = new_capacity;
    }
    Quad *quad = &quads[quad_count++];
    snprintf(quad->operation, sizeof(quad->operation), "%s", operation);
    quad->argument1 = argument1 == NULL ? NULL : duplicate_string(argument1);
    quad->argument2 = argument2 == NULL ? NULL : duplicate_string(argument2);
    quad->result = result == NULL ? NULL : duplicate_string(result);
}

static char *new_temporary(void) {
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "%%%u", next_temporary++);
    return duplicate_string(buffer);
}

static void push_scope(void) {
    if (scope_depth + 1 >= MAX_SCOPES) {
        fail("Maximum nested block depth exceeded");
    }
    scope_depth++;
    scope_starts[scope_depth] = symbol_count;
}

static void pop_scope(void) {
    if (scope_depth > 0) {
        symbol_count = scope_starts[scope_depth];
        scope_depth--;
    }
}

static void ensure_name_available(Token *identifier) {
    size_t start = scope_starts[scope_depth];
    for (size_t i = start; i < symbol_count; i++) {
        if (strcmp(symbols[i].name, identifier->text) == 0) {
            fail_at(identifier->line, "Duplicate declaration of '%s'", identifier->text);
        }
    }
}

static void append_variable_label(const char *label) {
    if (variable_count == variable_capacity) {
        size_t new_capacity = variable_capacity == 0 ? 32 : variable_capacity * 2;
        char (*new_labels)[32] = (char (*)[32])realloc(variable_labels, new_capacity * sizeof(*variable_labels));
        if (new_labels == NULL) {
            fail("Out of memory");
        }
        variable_labels = new_labels;
        variable_capacity = new_capacity;
    }
    snprintf(variable_labels[variable_count++], 32, "%s", label);
}

static Symbol *declare_variable(Token *identifier) {
    ensure_name_available(identifier);
    if (symbol_count == symbol_capacity) {
        size_t new_capacity = symbol_capacity == 0 ? 64 : symbol_capacity * 2;
        Symbol *new_symbols = (Symbol *)realloc(symbols, new_capacity * sizeof(Symbol));
        if (new_symbols == NULL) {
            fail_at(identifier->line, "Out of memory");
        }
        symbols = new_symbols;
        symbol_capacity = new_capacity;
    }
    Symbol *symbol = &symbols[symbol_count++];
    memset(symbol, 0, sizeof(*symbol));
    snprintf(symbol->name, sizeof(symbol->name), "%s", identifier->text);
    snprintf(symbol->label, sizeof(symbol->label), "v%zu", variable_count);
    symbol->is_constant = false;
    symbol->line = identifier->line;
    append_variable_label(symbol->label);
    return symbol;
}

static Symbol *declare_constant(Token *identifier, int64_t value) {
    ensure_name_available(identifier);
    if (symbol_count == symbol_capacity) {
        size_t new_capacity = symbol_capacity == 0 ? 64 : symbol_capacity * 2;
        Symbol *new_symbols = (Symbol *)realloc(symbols, new_capacity * sizeof(Symbol));
        if (new_symbols == NULL) {
            fail_at(identifier->line, "Out of memory");
        }
        symbols = new_symbols;
        symbol_capacity = new_capacity;
    }
    Symbol *symbol = &symbols[symbol_count++];
    memset(symbol, 0, sizeof(*symbol));
    snprintf(symbol->name, sizeof(symbol->name), "%s", identifier->text);
    symbol->is_constant = true;
    symbol->constant_value = value;
    symbol->line = identifier->line;
    return symbol;
}

static Symbol *lookup_symbol(Token *identifier) {
    for (size_t i = symbol_count; i > 0; i--) {
        if (strcmp(symbols[i - 1].name, identifier->text) == 0) {
            return &symbols[i - 1];
        }
    }
    fail_at(identifier->line, "Undefined identifier '%s'", identifier->text);
    return NULL;
}

static Symbol *require_writable(Token *identifier) {
    if (current_type() == TK_LBRACKET) {
        fail_at(current_token()->line, "Array references are outside this assignment subset");
    }
    Symbol *symbol = lookup_symbol(identifier);
    if (symbol->is_constant) {
        fail_at(identifier->line, "Cannot assign to constant '%s'", identifier->text);
    }
    return symbol;
}

static char *intern_string(const char *value) {
    for (size_t i = 0; i < string_count; i++) {
        if (strcmp(strings[i].value, value) == 0) {
            return strings[i].label;
        }
    }
    if (string_count == string_capacity) {
        size_t new_capacity = string_capacity == 0 ? 16 : string_capacity * 2;
        StringData *new_strings = (StringData *)realloc(strings, new_capacity * sizeof(StringData));
        if (new_strings == NULL) {
            fail("Out of memory");
        }
        strings = new_strings;
        string_capacity = new_capacity;
    }
    StringData *string = &strings[string_count++];
    snprintf(string->label, sizeof(string->label), "s%u", next_string++);
    string->value = duplicate_string(value);
    return string->label;
}

static void emit_string(const char *value) {
    if (value[0] == '\0') {
        return;
    }
    emit_quad("PRINT_STRING", intern_string(value), NULL, NULL);
}

static void emit_line_ending(void) {
    emit_string("\n");
}

static Value parse_expression(void);
static Value parse_additive_expression(void);
static Value parse_multiplicative_expression(void);
static Value parse_unary_expression(void);

static int64_t evaluate_constant(const char *operation, int64_t left, int64_t right) {
    if (strcmp(operation, "ADD") == 0) return left + right;
    if (strcmp(operation, "SUB") == 0) return left - right;
    if (strcmp(operation, "MUL") == 0) return left * right;
    if (right == 0) fail("Division by zero in constant expression");
    if (strcmp(operation, "DIV") == 0) return left / right;
    return left % right;
}

static Value combine(const char *operation, Value left, Value right) {
    if (constant_expression) {
        if (!left.is_constant || !right.is_constant) {
            fail("Constant expression refers to a non-constant variable");
        }
        return constant_value(evaluate_constant(operation, left.constant_value, right.constant_value));
    }
    char *temporary = new_temporary();
    emit_quad(operation, left.operand, right.operand, temporary);
    return operand_value(temporary);
}

static Value unary_value(const char *operation, Value value) {
    if (constant_expression) {
        if (!value.is_constant) {
            fail("Constant expression refers to a non-constant variable");
        }
        if (strcmp(operation, "POS") == 0) return constant_value(value.constant_value);
        if (strcmp(operation, "NEG") == 0) return constant_value(-value.constant_value);
        return constant_value(value.constant_value == 0 ? 1 : 0);
    }
    char *temporary = new_temporary();
    emit_quad(operation, value.operand, NULL, temporary);
    return operand_value(temporary);
}

static Value parse_primary_expression(void) {
    if (match(TK_LPAREN)) {
        Value value = parse_expression();
        expect(TK_RPAREN);
        return value;
    }
    if (current_type() == TK_NUMBER) {
        Token *number = expect(TK_NUMBER);
        return constant_value(number->number);
    }
    Token *identifier = expect(TK_IDENT);
    if (current_type() == TK_LBRACKET) {
        fail_at(current_token()->line, "Array references are outside this assignment subset");
    }
    if (current_type() == TK_LPAREN) {
        fail_at(identifier->line, "Function calls are outside this assignment subset");
    }
    Symbol *symbol = lookup_symbol(identifier);
    if (symbol->is_constant) {
        return constant_value(symbol->constant_value);
    }
    char operand[80];
    snprintf(operand, sizeof(operand), "@%s", symbol->label);
    return operand_value(operand);
}

static Value parse_unary_expression(void) {
    if (current_type() == TK_PLUS || current_type() == TK_MINUS || current_type() == TK_NOT) {
        TokenType type = current_type();
        position++;
        Value operand = parse_unary_expression();
        if (type == TK_PLUS) return unary_value("POS", operand);
        if (type == TK_MINUS) return unary_value("NEG", operand);
        return unary_value("NOT", operand);
    }
    return parse_primary_expression();
}

static Value parse_multiplicative_expression(void) {
    Value value = parse_unary_expression();
    while (current_type() == TK_MULT || current_type() == TK_DIV || current_type() == TK_MOD) {
        TokenType type = current_type();
        position++;
        Value right = parse_unary_expression();
        value = combine(type == TK_MULT ? "MUL" : type == TK_DIV ? "DIV" : "MOD", value, right);
    }
    return value;
}

static Value parse_additive_expression(void) {
    Value value = parse_multiplicative_expression();
    while (current_type() == TK_PLUS || current_type() == TK_MINUS) {
        TokenType type = current_type();
        position++;
        Value right = parse_multiplicative_expression();
        value = combine(type == TK_PLUS ? "ADD" : "SUB", value, right);
    }
    return value;
}

static Value parse_expression(void) {
    return parse_additive_expression();
}

static void parse_const_definition(void) {
    Token *identifier = expect(TK_IDENT);
    ensure_name_available(identifier);
    expect(TK_ASSIGN);
    bool previous_mode = constant_expression;
    constant_expression = true;
    Value initial_value = parse_expression();
    constant_expression = previous_mode;
    if (!initial_value.is_constant) {
        fail_at(identifier->line, "Constant initializer must be a constant expression");
    }
    declare_constant(identifier, initial_value.constant_value);
}

static void parse_variable_definition(void) {
    Token *identifier = expect(TK_IDENT);
    Symbol *symbol = declare_variable(identifier);
    if (match(TK_ASSIGN)) {
        Value initial_value = parse_expression();
        char target[80];
        snprintf(target, sizeof(target), "@%s", symbol->label);
        emit_quad("ASSIGN", initial_value.operand, NULL, target);
    }
}

static void parse_declaration(void) {
    bool is_constant = match(TK_CONST);
    expect(TK_INT);
    if (is_constant) {
        parse_const_definition();
        while (match(TK_COMMA)) parse_const_definition();
    } else {
        parse_variable_definition();
        while (match(TK_COMMA)) parse_variable_definition();
    }
    expect(TK_SEMICOLON);
}

static void parse_printf(void) {
    expect(TK_PRINTF);
    expect(TK_LPAREN);
    if (current_type() == TK_STRING) {
        Token *format_token = expect(TK_STRING);
        const char *format = format_token->string_value;
        Value *arguments = NULL;
        size_t argument_count = 0;
        size_t argument_capacity = 0;
        while (match(TK_COMMA)) {
            if (argument_count == argument_capacity) {
                size_t new_capacity = argument_capacity == 0 ? 4 : argument_capacity * 2;
                Value *new_arguments = (Value *)realloc(arguments, new_capacity * sizeof(Value));
                if (new_arguments == NULL) {
                    fail("Out of memory");
                }
                arguments = new_arguments;
                argument_capacity = new_capacity;
            }
            arguments[argument_count++] = parse_expression();
        }
        expect(TK_RPAREN);
        expect(TK_SEMICOLON);

        size_t placeholders = 0;
        for (size_t i = 0; format[i] != '\0'; i++) {
            if (format[i] == '%' && format[i + 1] == 'd') {
                placeholders++;
                i++;
            }
        }
        if (placeholders > argument_count) {
            fail_at(format_token->line, "printf format expects %zu value(s), received %zu",
                    placeholders, argument_count);
        }
        Buffer literal = {0};
        size_t argument_index = 0;
        for (size_t i = 0; format[i] != '\0';) {
            if (format[i] == '%' && format[i + 1] == 'd') {
                emit_string(literal.data == NULL ? "" : literal.data);
                literal.length = 0;
                if (literal.data != NULL) literal.data[0] = '\0';
                emit_quad("PRINT_INT", arguments[argument_index++].operand, NULL, NULL);
                i += 2;
            } else {
                buffer_append_char(&literal, format[i++]);
            }
        }
        emit_string(literal.data == NULL ? "" : literal.data);
        while (argument_index < argument_count) {
            emit_quad("PRINT_INT", arguments[argument_index++].operand, NULL, NULL);
        }
        if (strlen(format) == 0 || format[strlen(format) - 1] != '\n') {
            emit_line_ending();
        }
        free(arguments);
        free(literal.data);
    } else {
        Value value = parse_expression();
        expect(TK_RPAREN);
        expect(TK_SEMICOLON);
        emit_quad("PRINT_INT", value.operand, NULL, NULL);
        emit_line_ending();
    }
}

static void parse_scanf(void) {
    expect(TK_SCANF);
    expect(TK_LPAREN);
    Token *identifier = expect(TK_IDENT);
    Symbol *target = require_writable(identifier);
    char destination[80];
    snprintf(destination, sizeof(destination), "@%s", target->label);
    emit_quad("READ", NULL, NULL, destination);
    while (match(TK_COMMA)) {
        identifier = expect(TK_IDENT);
        target = require_writable(identifier);
        snprintf(destination, sizeof(destination), "@%s", target->label);
        emit_quad("READ", NULL, NULL, destination);
    }
    expect(TK_RPAREN);
    expect(TK_SEMICOLON);
}

static void parse_assignment_or_expression(void) {
    if (current_type() == TK_IDENT && type_at(1) == TK_ASSIGN) {
        Token *identifier = expect(TK_IDENT);
        Symbol *target = require_writable(identifier);
        expect(TK_ASSIGN);
        char destination[80];
        snprintf(destination, sizeof(destination), "@%s", target->label);
        if (match(TK_GETINT)) {
            expect(TK_LPAREN);
            expect(TK_RPAREN);
            expect(TK_SEMICOLON);
            emit_quad("READ", NULL, NULL, destination);
        } else {
            Value value = parse_expression();
            expect(TK_SEMICOLON);
            emit_quad("ASSIGN", value.operand, NULL, destination);
        }
        return;
    }
    parse_expression();
    expect(TK_SEMICOLON);
}

static void parse_statement(void);

static void parse_block(bool create_scope) {
    expect(TK_LBRACE);
    if (create_scope) push_scope();
    while (current_type() != TK_RBRACE && current_type() != TK_EOF) {
        if (current_type() == TK_CONST || current_type() == TK_INT) {
            parse_declaration();
        } else {
            parse_statement();
        }
    }
    expect(TK_RBRACE);
    if (create_scope) pop_scope();
}

static void parse_statement(void) {
    if (match(TK_SEMICOLON)) return;
    if (current_type() == TK_LBRACE) {
        parse_block(true);
        return;
    }
    if (current_type() == TK_PRINTF) {
        parse_printf();
        return;
    }
    if (current_type() == TK_SCANF) {
        parse_scanf();
        return;
    }
    if (match(TK_RETURN)) {
        if (current_type() != TK_SEMICOLON) parse_expression();
        expect(TK_SEMICOLON);
        emit_quad("HALT", NULL, NULL, NULL);
        return;
    }
    if (current_type() == TK_GETINT) {
        fail("getint must be the right side of an assignment");
    }
    if (current_type() == TK_IDENT || current_type() == TK_NUMBER ||
        current_type() == TK_PLUS || current_type() == TK_MINUS ||
        current_type() == TK_NOT || current_type() == TK_LPAREN) {
        parse_assignment_or_expression();
        return;
    }
    fail("Statement is not supported in this assignment subset");
}

static bool is_main_header(void) {
    return (current_type() == TK_INT || current_type() == TK_VOID) && type_at(1) == TK_MAIN;
}

static bool is_function_header(void) {
    return current_type() == TK_INT && type_at(1) == TK_IDENT && type_at(2) == TK_LPAREN;
}

static void parse_main_function(void) {
    if (match(TK_VOID)) {
        expect(TK_MAIN);
    } else {
        expect(TK_INT);
        expect(TK_MAIN);
    }
    expect(TK_LPAREN);
    expect(TK_RPAREN);
    parse_block(true);
}

static void parse_program(void) {
    while (current_type() == TK_CONST || (current_type() == TK_INT && !is_main_header())) {
        if (is_function_header()) {
            fail("Only main and scalar declarations are supported in this assignment");
        }
        parse_declaration();
    }
    parse_main_function();
    if (current_type() != TK_EOF) {
        fail("Unexpected token after main function: '%s'", current_token()->text);
    }
    if (quad_count == 0 || strcmp(quads[quad_count - 1].operation, "HALT") != 0) {
        emit_quad("HALT", NULL, NULL, NULL);
    }
}

static bool optimization_enabled(void) {
    const char *value = getenv("COMPILER_OPTIMIZE");
    return value != NULL && (strcmp(value, "1") == 0 || strcmp(value, "true") == 0 ||
                             strcmp(value, "TRUE") == 0 || strcmp(value, "on") == 0);
}

static Quad *optimize_quads(size_t *optimized_count) {
    Quad *result = (Quad *)malloc((quad_count == 0 ? 1 : quad_count) * sizeof(Quad));
    if (result == NULL) fail("Out of memory");
    size_t count = 0;
    for (size_t i = 0; i < quad_count; i++) {
        Quad *quad = &quads[i];
        if (strcmp(quad->operation, "ASSIGN") == 0 && quad->argument1 != NULL &&
            quad->result != NULL && strcmp(quad->argument1, quad->result) == 0) {
            continue;
        }
        result[count++] = *quad;
    }
    *optimized_count = count;
    return result;
}

static void write_intermediate_code(const char *path, const Quad *code, size_t count) {
    if (path == NULL || path[0] == '\0') return;
    FILE *file = fopen(path, "wb");
    if (file == NULL) fail("Unable to write intermediate code file '%s'", path);
    for (size_t i = 0; i < count; i++) {
        fprintf(file, "%zu: (%s, %s, %s, %s)\n", i, code[i].operation,
                code[i].argument1 == NULL ? "_" : code[i].argument1,
                code[i].argument2 == NULL ? "_" : code[i].argument2,
                code[i].result == NULL ? "_" : code[i].result);
    }
    fclose(file);
}

static void append_byte_directive(Buffer *output, const char *label, const unsigned char *bytes,
                                  size_t length) {
    size_t byte_count = length + 1;
    for (size_t start = 0; start < byte_count; start += 16) {
        size_t end = start + 16 < byte_count ? start + 16 : byte_count;
        if (start == 0) buffer_append_format(output, "%s: .byte ", label);
        else buffer_append(output, "    .byte ");
        for (size_t i = start; i < end; i++) {
            if (i > start) buffer_append(output, ", ");
            buffer_append_format(output, "%u", i == length ? 0U : (unsigned int)bytes[i]);
        }
        buffer_append_char(output, '\n');
    }
}

static const char *storage_label(const char *operand, char *buffer, size_t buffer_size) {
    if (operand[0] == '@') {
        snprintf(buffer, buffer_size, "%s", operand + 1);
    } else if (operand[0] == '%') {
        snprintf(buffer, buffer_size, "t%s", operand + 1);
    } else {
        snprintf(buffer, buffer_size, "%s", operand);
    }
    return buffer;
}

static void emit_load(Buffer *output, const char *operand, const char *register_name) {
    if (operand[0] == '#') {
        long long value = strtoll(operand + 1, NULL, 10);
        int32_t word = (int32_t)(uint32_t)value;
        buffer_append_format(output, "li %s, %" PRId32 "\n", register_name, word);
    } else {
        char label[80];
        buffer_append_format(output, "lw %s, %s\n", register_name,
                             storage_label(operand, label, sizeof(label)));
    }
}

static void emit_arithmetic(Buffer *output, const Quad *quad) {
    emit_load(output, quad->argument1, "$t0");
    if (strcmp(quad->operation, "NEG") == 0) {
        buffer_append(output, "subu $t2, $zero, $t0\n");
    } else if (strcmp(quad->operation, "POS") == 0) {
        buffer_append(output, "move $t2, $t0\n");
    } else if (strcmp(quad->operation, "NOT") == 0) {
        buffer_append(output, "sltiu $t2, $t0, 1\n");
    } else {
        emit_load(output, quad->argument2, "$t1");
        if (strcmp(quad->operation, "ADD") == 0) buffer_append(output, "addu $t2, $t0, $t1\n");
        else if (strcmp(quad->operation, "SUB") == 0) buffer_append(output, "subu $t2, $t0, $t1\n");
        else if (strcmp(quad->operation, "MUL") == 0) buffer_append(output, "mult $t0, $t1\nmflo $t2\n");
        else if (strcmp(quad->operation, "DIV") == 0) buffer_append(output, "div $t0, $t1\nmflo $t2\n");
        else if (strcmp(quad->operation, "MOD") == 0) buffer_append(output, "div $t0, $t1\nmfhi $t2\n");
    }
    char label[80];
    buffer_append_format(output, "sw $t2, %s\n", storage_label(quad->result, label, sizeof(label)));
}

static char *generate_mips(const Quad *code, size_t count) {
    Buffer output = {0};
    buffer_append(&output, ".data\n");
    if (variable_count > 0 || next_temporary > 0) {
        buffer_append(&output, ".align 2\n");
        for (size_t i = 0; i < variable_count; i++) {
            buffer_append_format(&output, "%s: .word 0\n", variable_labels[i]);
        }
        for (unsigned int i = 0; i < next_temporary; i++) {
            buffer_append_format(&output, "t%u: .word 0\n", i);
        }
    }
    for (size_t i = 0; i < string_count; i++) {
        append_byte_directive(&output, strings[i].label,
                              (const unsigned char *)strings[i].value, strlen(strings[i].value));
    }
    buffer_append(&output, ".text\n.globl main\nmain:\n");
    for (size_t i = 0; i < count; i++) {
        const Quad *quad = &code[i];
        if (strcmp(quad->operation, "ASSIGN") == 0) {
            emit_load(&output, quad->argument1, "$t0");
            char label[80];
            buffer_append_format(&output, "sw $t0, %s\n", storage_label(quad->result, label, sizeof(label)));
        } else if (strcmp(quad->operation, "READ") == 0) {
            buffer_append(&output, "li $v0, 5\nsyscall\n");
            char label[80];
            buffer_append_format(&output, "sw $v0, %s\n", storage_label(quad->result, label, sizeof(label)));
        } else if (strcmp(quad->operation, "PRINT_INT") == 0) {
            emit_load(&output, quad->argument1, "$a0");
            buffer_append(&output, "li $v0, 1\nsyscall\n");
        } else if (strcmp(quad->operation, "PRINT_STRING") == 0) {
            buffer_append_format(&output, "la $a0, %s\nli $v0, 4\nsyscall\n", quad->argument1);
        } else if (strcmp(quad->operation, "HALT") == 0) {
            buffer_append(&output, "li $v0, 10\nsyscall\n");
        } else {
            emit_arithmetic(&output, quad);
        }
    }
    return output.data == NULL ? duplicate_string("") : output.data;
}

static char *read_source_file(size_t *length) {
    FILE *file = fopen(INPUT_FILE, "rb");
    if (file == NULL) {
        fail_at(1, "Unable to open testfile.txt");
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        fail_at(1, "Unable to read testfile.txt");
    }
    long file_size = ftell(file);
    if (file_size < 0 || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        fail_at(1, "Unable to read testfile.txt");
    }
    char *text = (char *)malloc((size_t)file_size + 1);
    if (text == NULL) {
        fclose(file);
        fail_at(1, "Out of memory");
    }
    size_t bytes_read = fread(text, 1, (size_t)file_size, file);
    fclose(file);
    if (bytes_read != (size_t)file_size) {
        free(text);
        fail_at(1, "Unable to read testfile.txt");
    }
    if (bytes_read >= 3 && (unsigned char)text[0] == 0xef && (unsigned char)text[1] == 0xbb &&
        (unsigned char)text[2] == 0xbf) {
        memmove(text, text + 3, bytes_read - 3);
        bytes_read -= 3;
    }
    text[bytes_read] = '\0';
    *length = bytes_read;
    return text;
}

static void write_empty_output(void) {
    FILE *file = fopen(OUTPUT_FILE, "wb");
    if (file != NULL) fclose(file);
}

static void free_compiler_memory(void) {
    for (size_t i = 0; i < token_count; i++) {
        free(tokens[i].text);
        free(tokens[i].string_value);
    }
    for (size_t i = 0; i < quad_count; i++) {
        free(quads[i].argument1);
        free(quads[i].argument2);
        free(quads[i].result);
    }
    for (size_t i = 0; i < string_count; i++) free(strings[i].value);
    free(tokens);
    free(quads);
    free(symbols);
    free(variable_labels);
    free(strings);
}

int main(void) {
    if (setjmp(compile_error_jump) != 0) {
        fprintf(stderr, "Compile error at line %d: %s\n", error_line, error_message);
        write_empty_output();
        free_compiler_memory();
        return 1;
    }

    size_t source_size = 0;
    char *source = read_source_file(&source_size);
    lex_source(source, source_size);
    free(source);
    position = 0;
    scope_depth = 0;
    scope_starts[0] = 0;
    parse_program();

    bool optimize = optimization_enabled();
    size_t final_count = quad_count;
    Quad *optimized = NULL;
    if (optimize) {
        optimized = optimize_quads(&final_count);
    }
    write_intermediate_code(getenv("COMPILER_IR_BEFORE"), quads, quad_count);
    write_intermediate_code(getenv("COMPILER_IR_AFTER"), optimize ? optimized : quads, final_count);

    char *assembly = generate_mips(optimize ? optimized : quads, final_count);
    FILE *output = fopen(OUTPUT_FILE, "wb");
    if (output == NULL) {
        fail_at(1, "Unable to create mips.txt");
    }
    size_t assembly_length = strlen(assembly);
    bool write_ok = fwrite(assembly, 1, assembly_length, output) == assembly_length;
    fclose(output);
    if (!write_ok) {
        fail_at(1, "Unable to write mips.txt");
    }

    free(optimized);
    free(assembly);
    free_compiler_memory();
    return 0;
}
