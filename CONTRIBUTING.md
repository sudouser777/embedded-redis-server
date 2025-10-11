# Contributing to Embedded Redis Server

First off, thank you for considering contributing to Embedded Redis Server! 🎉

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Pull Request Process](#pull-request-process)
- [Style Guidelines](#style-guidelines)
- [Testing Guidelines](#testing-guidelines)

## Code of Conduct

This project and everyone participating in it is governed by respect and professionalism. Please be kind and courteous in all interactions.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check existing issues to avoid duplicates. When creating a bug report, include:

- **Clear title and description**
- **Steps to reproduce**
- **Expected vs actual behavior**
- **Code samples** (if applicable)
- **Version information** (Java, Kotlin, embedded-redis-server version)
- **Log output** (if relevant)

Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.yml) when filing issues.

### Suggesting Features

Feature suggestions are welcome! Please:

- Use the [Feature Request template](.github/ISSUE_TEMPLATE/feature_request.yml)
- Explain the problem you're trying to solve
- Describe your proposed solution
- Provide usage examples
- Consider backwards compatibility

### Your First Code Contribution

Unsure where to begin? Look for issues labeled:

- `good first issue` - Good for newcomers
- `help wanted` - Issues where we need help
- `documentation` - Improvements to docs

### Pull Requests

We actively welcome your pull requests:

1. Fork the repo and create your branch from `main`
2. Make your changes
3. Add tests if you've added code
4. Update documentation if needed
5. Ensure tests pass
6. Submit your pull request!

## Development Setup

### Prerequisites

- Java 21 or higher
- Gradle 8.x (included via wrapper)
- Git

### Setting Up Your Development Environment

1. **Fork and clone the repository**

   ```bash
   git clone https://github.com/sudouser777/embedded-redis-server.git
   cd embedded-redis-server
   ```

2. **Build the project**

   ```bash
   ./gradlew build
   ```

3. **Run tests**

   ```bash
   ./gradlew test
   ```

4. **Run the server locally**

   ```bash
   ./gradlew run
   ```

### Project Structure

```
embedded-redis-server/
├── src/
│   ├── main/kotlin/io/github/embeddedredis/
│   │   ├── CommandHandler.kt          # Command processing
│   │   ├── DataStore.kt               # In-memory storage
│   │   ├── RedisServer.kt             # TCP server
│   │   ├── RespProtocol.kt            # RESP2 protocol
│   │   ├── EmbeddedRedisProperties.kt # Spring Boot properties
│   │   └── EmbeddedRedisAutoConfiguration.kt
│   └── test/kotlin/io/github/embeddedredis/
│       └── RedisServerTest.kt         # Integration tests
├── build.gradle                       # Build configuration
└── README.md                          # Documentation
```

## Pull Request Process

### 1. Create a Feature Branch

```bash
git checkout -b feature/my-new-feature
```

### 2. Make Your Changes

- Write clear, concise commit messages
- Keep commits focused and atomic
- Reference issues in commit messages (`fixes #123`)

### 3. Test Your Changes

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests "RedisServerTest.testPing"

# Build everything
./gradlew build
```

### 4. Update Documentation

- Update README.md if you've changed APIs
- Add examples to EXAMPLES.md if applicable
- Update JavaDoc/KDoc comments

### 5. Commit Your Changes

```bash
git add .
git commit -m "feat: add INCR command support

- Implement INCR command in CommandHandler
- Add unit tests for INCR
- Update README with INCR documentation

Fixes #123"
```

**Commit Message Format:**

```
type(scope): subject

body

footer
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style changes (formatting)
- `refactor`: Code refactoring
- `test`: Adding tests
- `chore`: Maintenance tasks

### 6. Push and Create Pull Request

```bash
git push origin feature/my-new-feature
```

Then open a Pull Request on GitHub with:

- Clear title describing the change
- Description of what changed and why
- Reference to related issues
- Screenshots (if UI/output changes)

### 7. Code Review

- Address review feedback
- Keep the PR updated with main branch
- Be responsive to comments

## Style Guidelines

### Kotlin Style

Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

- Use 4 spaces for indentation
- Use camelCase for variables and functions
- Use PascalCase for classes
- Maximum line length: 120 characters

**Example:**

```kotlin
class CommandHandler(private val dataStore: DataStore) {
    fun handleSet(args: List<Any?>): Any? {
        if (args.size < 3) {
            return RespError("ERR wrong number of arguments")
        }
        val key = args[1] as String
        val value = args[2] as String
        return dataStore.set(key, value)
    }
}
```

### Documentation Style

- Add KDoc comments for public APIs
- Explain "why" not just "what"
- Include usage examples

```kotlin
/**
 * Starts the Redis server and begins accepting connections.
 *
 * The server will bind to the configured host and port and
 * handle incoming Redis protocol commands.
 *
 * @throws IOException if the server cannot bind to the port
 */
fun start()
```

## Testing Guidelines

### Writing Tests

1. **Use descriptive test names**

   ```kotlin
   @Test
   fun `should return PONG when PING is called`() {
       // test implementation
   }
   ```

2. **Follow AAA pattern** (Arrange, Act, Assert)

   ```kotlin
   @Test
   fun `should store and retrieve value`() {
       // Arrange
       val server = RedisServer(port = 16379)
       server.start()
       val jedis = Jedis("localhost", 16379)

       // Act
       jedis.set("key", "value")
       val result = jedis.get("key")

       // Assert
       assertEquals("value", result)
   }
   ```

3. **Clean up resources**

   ```kotlin
   @AfterEach
   fun cleanup() {
       jedis.close()
       server.stop()
   }
   ```

### Test Coverage

- Aim for 80%+ code coverage
- Test happy paths and error cases
- Include edge cases
- Test concurrent operations

### Running Tests

```bash
# All tests
./gradlew test

# With coverage
./gradlew test jacocoTestReport

# Specific test class
./gradlew test --tests "RedisServerTest"

# Watch mode (rerun on changes)
./gradlew test --continuous
```

## Adding New Redis Commands

When adding a new Redis command:

1. **Update CommandHandler.kt**

   ```kotlin
   when (cmd) {
       // ...
       "INCR" -> handleIncr(command)
   }

   private fun handleIncr(args: List<Any?>): Any {
       // implementation
   }
   ```

2. **Add tests**

   ```kotlin
   @Test
   fun `should increment value with INCR`() {
       jedis.set("counter", "5")
       val result = jedis.incr("counter")
       assertEquals(6L, result)
   }
   ```

3. **Update documentation**
   - Add command to README.md
   - Add example to EXAMPLES.md

## Questions?

- Open a [Discussion](https://github.com/sudouser777/embedded-redis-server/discussions)
- Ask in your Pull Request
- Check existing [Issues](https://github.com/sudouser777/embedded-redis-server/issues)

## Recognition

Contributors will be recognized in:
- GitHub contributors page
- Release notes
- Project README (for significant contributions)

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

---

**Thank you for contributing to Embedded Redis Server!** 🚀
