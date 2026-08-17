--- section: kb-section ---
{snippet_section}{doc_blocks}

--- section: kb-doc-block ---
<content data-ragent-doc-id="{doc_id}">
{chunks}
</content>

--- section: kb-doc-block-anonymous ---
<content>
{chunks}
</content>

--- section: snippet-rules ---
<rules>
{rules}
</rules>

--- section: mcp-section ---
{snippet_section}<data>
{body}
</data>

--- section: mcp-intent-rules ---
<rules>
{rules}
</rules>

--- section: mcp-error ---
<errors>
{error_list}
</errors>

--- section: sub-question-kb-wrapper ---
<document index="{index}">
<question>{question}</question>
{context}
</document>

--- section: sub-question-mcp-wrapper ---
<result index="{index}">
<question>{question}</question>
{context}
</result>

--- section: single-question ---
<question>{question}</question>

--- section: multi-questions ---
<questions>
{questions}
</questions>

--- section: kb-evidence ---
<documents>
{body}
</documents>

--- section: mcp-evidence ---
<tool-data>
{body}
</tool-data>

--- section: summary-wrapper ---
<conversation-summary>
{content}
</conversation-summary>
