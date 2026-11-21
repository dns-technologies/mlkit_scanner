# Comments respect encapsulation

This rule applies to documentation and inline comments throughout the plugin,
including Dart, Android and iOS code.

For declaration order and language-specific lint checks, see the
[code style guidelines](code-style-guidelines.md).

Describe an entity's purpose and its own contract: the meaning of its data,
operations, preconditions, results, errors, resource ownership and concurrency
guarantees. Describe callbacks by when they run and what they receive or return.

Do not explain an entity through its role in another component that it does not
depend on. An interface must not describe its concrete implementations; a model
must not describe how a particular widget, controller or runtime uses it. A
callback must not assume which component supplied it.

Mention another component or technology only when it is an explicit dependency,
part of the declared API or necessary to explain the entity's own data format.
An import elsewhere in the same file does not establish a dependency for every
entity in that file. Generic caller obligations, such as keeping a borrowed
buffer alive until a callback returns, are part of the contract and should remain
documented.

## Examples

| Entity | Avoid | Prefer |
| --- | --- | --- |
| `ScannerConsumer` | The runtime uses the controller to notify widgets. | Camera consumer contract for desired settings, capture readiness and result delivery. |
| Capture state | Display the widget's saved image. | No active ownership of the camera. |
| Configuration decoder | Dart already validates these settings. | Decodes the recognition area from command arguments. |
| Completion callback | Removes this reply from its owning lease. | Completion callback invoked before delivering the response. |
| Geometry model | Flutter uses this to crop its texture. | Maps preview coordinates to the source buffer. |

CameraX-specific constraints belong in code that actually uses CameraX, such as
its camera adapter, rather than in a general camera contract. Descriptions of
cross-component flows belong in architecture documentation or in the component
that explicitly coordinates those dependencies.

## Document purpose and contract, not the implementation

Documentation comments (`///` and `/** ... */`) explain why an entity exists and
what callers may rely on. Do not paraphrase its name, initializer, return
expression or sequence of statements. Do not copy tuning values, flag values,
collection contents or constructor arguments into prose when the declaration
already states them.

For example, document a resolution selector as "Resolution policy shared by
preview and analysis", not by repeating its aspect ratio, target dimensions and
fallback enum. Document an animation's role in focus feedback, not its duration
and the arithmetic used to split its timeline.

Keep information needed to use the API correctly: units, coordinate systems,
valid ranges, meaningful defaults, failure behavior, ownership, threading and
completion guarantees. A numeric example that explains normalized coordinates
is useful; restating the literal assigned to a private constant is not.

If a comment adds nothing beyond the declaration, remove it rather than inventing
an explanation. Implementation reasoning belongs inside the body, next to the
relevant code, using an ordinary comment. Such comments may refer to concrete
operations and values when explaining a constraint or a non-obvious choice.
Do not move a redundant description into the body just to keep it.

Describe the operation's purpose, not the mechanism used to achieve it. This
applies to private helpers as well as public APIs. Avoid narrating fallback
branches, lookup tables, identity comparisons, bookkeeping, internal delegation
or the order of implementation steps. For example, prefer "Platform code of this
barcode format" over "Platform code, falling back to the unknown format code when
unmapped". Keep enum fallback policy in the code style guidelines rather than
repeating its implementation in accessor documentation.

Keep caller obligations and observable guarantees when they matter for correct
use, such as borrowed-data lifetimes or the thread on which a callback runs.
Describe those guarantees without explaining the locks, queues or state flags
used to provide them. Private synchronization fields may document which state
they protect; operation documentation should not narrate their use.

## Make side effects visible

Prefer a method name that communicates its meaningful side effects. A name that
looks like a query must not conceal resource acquisition, state mutation,
subscription changes or callback delivery. For example, use
`getOrCreateAnalysisExecutor` when obtaining the worker may create one.

If including every effect would make the name cumbersome or less readable,
keep a concise action name and document the additional observable effects.
For example, a subscription method may document that it cancels the previous
subscription; a disposal method may document how pending requests complete.
Describe the consequence, not the sequence of assignments that causes it.
Do not list effects already clear from the name or private bookkeeping that
callers cannot observe. Renaming an exported API still requires a compatibility
decision; document its effects without silently breaking existing callers.

## Document every enum value

Every enum value must have its own documentation comment directly above its
declaration, including values in private enums and enums with only one value.
Use `///` in Dart and Swift, and `/** ... */` in Kotlin. A comment above the enum
itself does not replace comments for its individual values. In Swift, declare
cases separately so each can carry its own comment.

Describe the value's meaning, state or recognized format. A short conventional
name such as "Code 128" or "EAN-13" is sufficient for a barcode format. This is
an explicit exception to removing comments that repeat an identifier: retain
documentation for every enum value even when its name already reads clearly.
Do not repeat its numeric transport code or unrelated implementation details.

## No comments above overrides

Do not place documentation or explanatory comments above overridden methods,
getters, setters or properties on any platform. This includes Dart `@override`,
Kotlin and Swift `override`, and Java `@Override`. Do not move the comment between
an override annotation and the declaration to bypass this rule.

Document the inherited contract at its declaration in the interface or base
class. If an override needs implementation-specific reasoning, put that comment
inside its body, next to the relevant code. Keep useful comments already inside
method bodies. Tool directives, such as targeted lint suppressions, are not
explanatory comments and must retain their required placement.

## Document construction above the type

Do not place documentation or explanatory comments above constructors or
initializers on any platform. This includes unnamed, named and factory
constructors in Dart, primary and secondary constructors in Kotlin, constructors
in Java, and Swift `init` declarations, including convenience and failable
initializers. Do not move comments between annotations and declarations.

Describe useful construction details in the owning class or type documentation:
defaults, validation, resource acquisition and other initialization guarantees.
Remove comments that merely repeat the type's purpose or the parameter names.
Keep documentation for fields declared in primary constructor parameters, useful
implementation comments inside constructor bodies and required tool directives.

## Review check

First, check that overridden members and constructors have no preceding
documentation or explanatory comments, including between annotations and
declarations. Construction details belong above the owning class or type.

Check that every enum value has its own documentation comment. For other
documentation comments, check that the text adds purpose or a useful contract
rather than repeating an identifier, literal or implementation step.

Check that method names communicate meaningful side effects. Where doing so
would make a name harder to read, verify that the documentation states the
additional observable effects without explaining their implementation.

For every comment naming another entity, ask:

1. Does this entity explicitly depend on it or expose it in its API?
2. Does the comment explain this entity's contract, rather than somebody else's
   behavior or policy?
3. Would the comment remain correct with another consumer or implementation that
   satisfies the same contract?

If not, describe the local guarantee or move the explanation to the component
that owns the interaction. Preserve useful units, coordinate conventions,
thread requirements and lifetime guarantees when rewriting comments.
