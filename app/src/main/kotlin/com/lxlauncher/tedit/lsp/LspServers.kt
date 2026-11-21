// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.lsp

import com.lxlauncher.tedit.ext.ExtensionStore

/**
 * Language servers Tedit knows how to start in Debian. A VS Code extension that would start one of these from its
 * JavaScript (the Python, clangd, Go, Rust, YAML, Bash extensions) points Tedit to the same server here instead, so
 * installing the extension brings its diagnostics and completion even though its own code does not run.
 */
object LspServers {
    class Preset(
        val id: String,
        val name: String,
        /** Tedit language ids the server handles. */
        val languages: Set<String>,
        /** LSP language id to announce per Tedit language, when it differs. */
        val lspIds: Map<String, String> = emptyMap(),
        val command: List<String>,
        /** The bash command that installs the server in Debian. */
        val install: String,
        /** Marketplace extensions that stand for this server. */
        val extensions: Set<String>,
    ) {
        fun lspLanguage(teditId: String) = lspIds[teditId] ?: teditId
    }

    private const val NODE = "apt-get update && apt-get install -y nodejs npm && npm install -g"

    val all = listOf(
        Preset("pylsp", "pylsp", setOf("python"), command = listOf("pylsp"),
            // Debian's pylsp only suggests its linters; without them it reports nothing.
            install = "apt-get update && apt-get install -y python3-pylsp python3-pyflakes python3-pycodestyle python3-autopep8",
            extensions = setOf("ms-python.python", "ms-python.vscode-pylance", "detachhead.basedpyright", "ms-pyright.pyright", "ms-python.pylint", "ms-python.flake8")),
        Preset("clangd", "clangd", setOf("c", "cpp"), command = listOf("clangd"),
            install = "apt-get update && apt-get install -y clangd",
            extensions = setOf("llvm-vs-code-extensions.vscode-clangd", "ms-vscode.cpptools")),
        Preset("gopls", "gopls", setOf("go"), command = listOf("gopls"),
            install = "apt-get update && apt-get install -y gopls",
            extensions = setOf("golang.go")),
        Preset("rust-analyzer", "rust-analyzer", setOf("rust"), command = listOf("rust-analyzer"),
            install = "apt-get update && apt-get install -y rust-analyzer",
            extensions = setOf("rust-lang.rust-analyzer")),
        Preset("tsls", "typescript-language-server", setOf("javascript", "typescript"), command = listOf("typescript-language-server", "--stdio"),
            install = "$NODE typescript typescript-language-server",
            extensions = setOf("ms-vscode.vscode-typescript-next", "dbaeumer.vscode-eslint")),
        Preset("bashls", "bash-language-server", setOf("shell"), lspIds = mapOf("shell" to "shellscript"), command = listOf("bash-language-server", "start"),
            install = "$NODE bash-language-server",
            extensions = setOf("mads-hartmann.bash-ide-vscode")),
        Preset("jsonls", "vscode-json-language-server", setOf("json"), command = listOf("vscode-json-language-server", "--stdio"),
            install = "$NODE vscode-langservers-extracted", extensions = emptySet()),
        Preset("cssls", "vscode-css-language-server", setOf("css"), command = listOf("vscode-css-language-server", "--stdio"),
            install = "$NODE vscode-langservers-extracted", extensions = emptySet()),
        Preset("htmlls", "vscode-html-language-server", setOf("html"), command = listOf("vscode-html-language-server", "--stdio"),
            install = "$NODE vscode-langservers-extracted", extensions = emptySet()),
        Preset("yamlls", "yaml-language-server", setOf("yaml"), command = listOf("yaml-language-server", "--stdio"),
            install = "$NODE yaml-language-server",
            extensions = setOf("redhat.vscode-yaml")),
    )

    fun forLanguage(teditId: String): Preset? = all.firstOrNull { teditId in it.languages }

    fun forExtension(e: ExtensionStore.Installed): Preset? = all.firstOrNull { e.id in it.extensions }
}
