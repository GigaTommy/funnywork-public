# Model probing

[简体中文](README.md) | **English**

The default entry point is the native Windows script tool. Extract the download and double-click `启动工具.cmd`, enter the address, sign in normally, and send a test message. Detection then runs automatically without copying scripts or importing/exporting JSON.

See the [Windows tool guide](NATIVE_MODEL_CHECK.md) for steps and verification scope.

`index.html` is the simplified download entry point. `index.manual.html` and `page-shell.manual.html` retain the previous manual browser tool as a fallback. The native window and new launch page use operational instructions rather than product or implementation descriptions.

The browser core is maintained in `scripts/checks/browser-model-probe.js`. Regenerate the legacy single-file HTML with `node scripts/build/build-enterprise-model-poc.cjs`; it updates only `index.manual.html` and preserves the new download entry point.

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.
