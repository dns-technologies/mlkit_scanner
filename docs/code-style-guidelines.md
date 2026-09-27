# Code style and declaration order

Apply the same conventions throughout each language, including tests and the
example. Follow the language's conventions rather than imposing one declaration
order on Dart, Kotlin and Swift. Generated sources are excluded.

Test filenames and grouping follow the [test guidelines](test-guidelines.md).

## Enum mapping fallbacks

On every platform, when an enum has an `unknown` value, use it when no mapping
matches. Decoding an unrecognized code must return that enum value rather than
throwing, asserting non-null or selecting an unrelated value. Encoding an
unmapped enum value must return the code assigned to `unknown`.

Keep the unknown code in one place, shared by the mapping and its fallback.
Do not implement the fallback by recursively calling the same mapping accessor.
In Dart, use `codes[this] ?? unknownCode` for encoding and
`values[code] ?? MyEnum.unknown` for decoding. The fallback must have the return
type of the mapping: an integer code cannot fall back directly to an enum value.

For enums without `unknown`, preserve their explicit decoding contract; do not
invent a fallback or silently substitute the first value. This rule concerns a
missing enum mapping, not unrelated validation errors.

## Dart

Use [Effective Dart](https://dart.dev/effective-dart) for style, documentation,
usage and API design. `flutter_lints` includes the recommended Dart rules and
Flutter-specific rules. Version 5 supports this package's minimum Dart 3.7 SDK;
do not raise the minimum SDK just to adopt a newer lint package.

Use this consistent class layout:

1. Constructors, with the unnamed constructor before named constructors.
2. Static fields and constants.
3. Instance fields.
4. Getters and setters, keeping each property's accessors together.
5. Methods, grouped by operation, with the main operation before its helpers.

Enum values precede these members. Keep lifecycle overrides in lifecycle order.
Preserve the relative order of field initializers. Do not sort fields by
mutability at the expense of related state or initialization dependencies.
Constructors-first is enforced by an optional SDK lint; the complete layout
above is our project convention, not a claim that Effective Dart mandates it.

In particular:

- Use `UpperCamelCase` for types and `lowerCamelCase` for members, variables,
  parameters and constants. Use `lowercase_with_underscores` for source files.
- Prefer clear, consistent names and explicit types at API boundaries. Let local
  variable types be inferred when their meaning is clear.
- Prefer `final` for state that is assigned once and `const` when appropriate.
  Initialize non-nullable fields in declarations or constructor initializer
  lists; use `late` only when initialization must actually be deferred.
- Use getters for property-like, side-effect-free access; use methods for work.
  Avoid redundant accessor methods for ordinary fields.
- Keep asynchronous return types and error propagation explicit. Await required
  work; mark intentional fire-and-forget work with `unawaited` and handle failures.
- Preserve existing public names and contracts during style-only cleanup.
  Renaming an exported API, including legacy acronym spellings, needs a separate
  compatibility decision.

Run `dart format` with the repository's configured width of 140. Do not hand-wrap
code differently to satisfy an editor's local settings.

The [comment guidelines](comment-guidelines.md) take precedence for placement:
document construction above the type and inherited contracts at their original
declarations, without comments above constructors or overrides.

## Kotlin

Follow the official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html#class-layout).
The primary constructor stays in the class header. Inside classes and objects:

1. Property declarations and `init` blocks, in their required execution order.
2. Secondary constructors.
3. Methods.
4. The companion object.

Apply the same layout within companion objects. Keep custom accessors attached
to their properties. Group related methods and keep overloads adjacent; use
high-level operations before their helpers where that preserves logical groups.
Keep interface implementations in interface declaration order, with private
helpers next to the operations they support. Put nested types near their use;
externally used nested types belong at the end.

Moving a property past an `init` block can change behavior. Preserve their
relative order; do not replace construction with lazy or deferred initialization
just to satisfy a visual grouping preference.

## Swift

Use the [Swift API Design Guidelines](https://www.swift.org/documentation/api-design-guidelines/)
for API naming and clarity. For declaration order, adopt the standard
[SwiftLint `type_contents_order`](https://realm.github.io/SwiftLint/type_contents_order.html)
configuration as the project convention:

1. Enum cases.
2. Type aliases and associated types.
3. Nested types.
4. Type properties (`static` / `class`).
5. Instance properties, including computed properties and their accessors.
6. Inspectable properties and outlets.
7. Initializers.
8. Type methods.
9. View lifecycle methods.
10. Interface Builder actions.
11. Other methods, grouped by operation, with the main operation before helpers.
12. Subscripts.
13. Deinitializers.

This is an explicit SwiftLint convention, not an ordering requirement imposed
by the Swift language. Preserve property initializer order within each group.

## Checks

Run these commands from the repository root:

```sh
dart format --output=none --set-exit-if-changed lib test example/lib
flutter analyze
flutter test
./example/android/gradlew -p tools/lint/android detekt
swiftlint lint --strict --config .swiftlint.yml
```

On Windows use `gradlew.bat`. Detekt runs in a separate Gradle project so lint
dependencies are not added to applications that consume the plugin. It checks
production Kotlin, tests and the example with `ClassOrdering`. SwiftLint checks
the iOS sources, tests and example; run it on a host with SwiftLint installed.

The tools enforce constructor placement in Dart and member categories in Kotlin
and Swift. Logical grouping, initialization dependencies and method call order
still need review. Do not add a custom call-graph linter: recursive calls cannot
have a strict textual order, and shared helpers may serve several operations.

When declarations move, preserve method bodies, attached comments and
annotations, resource lifetime and initialization order. Run the existing
platform tests after changes; declaration ordering must not change behavior.
