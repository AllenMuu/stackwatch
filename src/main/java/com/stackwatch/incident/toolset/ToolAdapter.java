package com.stackwatch.incident.toolset;

/**
 * Read-only adapter seam. Production provider adapters must be injected through ToolRegistry and
 * may receive only this fixed scope.
 */
public interface ToolAdapter {

    Toolset toolset();

    ToolRawResult execute(ToolScope scope);
}
