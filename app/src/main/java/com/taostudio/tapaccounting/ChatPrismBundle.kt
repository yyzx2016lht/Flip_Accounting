package com.taostudio.tapaccounting

import io.noties.prism4j.annotations.PrismBundle

@PrismBundle(
    include = [
        "c", "clike", "cpp", "css", "go", "java", "javascript", "json",
        "kotlin", "markdown", "markup", "python", "sql", "swift", "yaml"
    ],
    grammarLocatorClassName = ".ChatPrismGrammarLocator"
)
class ChatPrismBundle
