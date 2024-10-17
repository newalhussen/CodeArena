"use client";

import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { useEffect, useRef } from "react";

/** Controlled-ish CodeMirror: [value] seeds the editor, and changes are reported through [onChange]. Re-created when [resetKey] changes. */
export function CodeEditor({ value, language, resetKey, onChange }: { value: string; language: string; resetKey: string; onChange: (v: string) => void }) {
  const host = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!host.current) return;
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(), history(), keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]),
          language === "python" ? python() : javascript(),
          EditorView.updateListener.of((u) => { if (u.docChanged) onChangeRef.current(u.state.doc.toString()); }),
          EditorView.contentAttributes.of({ "aria-label": "Code editor" }),
        ],
      }),
    });
    return () => view.destroy();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetKey, language]);

  return <div ref={host} className="h-full min-h-[320px] overflow-hidden rounded-lg border border-line bg-card" />;
}
