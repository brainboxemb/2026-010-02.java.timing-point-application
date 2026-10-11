# Shared AntennaSettings control (issue #450)

The runtime creates exactly one `AntennaSettings` per AntennaManager and passes
that settings object to the corresponding SystemConductor.

```java
antennaSettings.update(edit -> {
    edit.inventoryEnabled(anyNodeOpen);
});
```

The lambda is a short transaction editor. All setters apply to an unpublished
copy; successful exit atomically publishes changed values. An unchanged update
publishes nothing. A thrown exception publishes nothing. Do not keep or reuse
the editor outside the lambda, and do not call `update` recursively from it.

`update()` is synchronous for publishing desired state. It **does not** wait
for device initialization, inventory, or physical completion. The settings event
wakes AntennaManager; AntennaManager uses its own execution lane to reconcile
requested and applied state. Physical retries remain manager-owned.

SystemConductor reads published TimingNode CURRENT status. It no longer stores
a second inventory-required DerivedProperty or per-node copied SourceProperty.
A single OPEN node still requires inventory. Only all CLOSED nodes disable it.

This first implementation exposes one mutable desired field. Add more fields
only when a concrete capability needs them; they then share one transaction.
