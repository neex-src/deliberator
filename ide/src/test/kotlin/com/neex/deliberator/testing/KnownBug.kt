package com.neex.deliberator.testing

/**
 * JUnit category of the tests that document a product bug (decision 24 for the IDE). `./gradlew test` excludes them;
 * `./gradlew knownBugs` (`bb ide:test --known-bugs`) runs only them and they must FAIL until the bug is fixed. The fix removes
 * `@Category(KnownBug::class)` in the same commit. Use on the method: JUnit 3 style `BasePlatformTestCase` tests take it too
 * (the platform runs them through the JUnit 4 runner), e.g. `@Category(KnownBug::class) fun testX()`.
 */
interface KnownBug
