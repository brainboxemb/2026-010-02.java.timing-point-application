package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.AppendOnlyRecordStore;

import java.util.ArrayList;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Default TimingData persistence mapping over a generic append-only record store.
 *
 * <p>This class owns TimingData-specific validation: codec mapping, TimingNode
 * identity and contiguous sequence. The lower I/O store remains unaware of all
 * three.</p>
 */
public final class DefaultTimingDataPersistence implements TimingDataPersistence {
    private final AppendOnlyRecordStore recordStore;
    private final NodeId timingNodeId;
    private final TimingDataCodec codec;

    public DefaultTimingDataPersistence(
            AppendOnlyRecordStore recordStore,
            NodeId timingNodeId,
            TimingDataCodec codec) {
        checkArgument(recordStore != null, "recordStore must not be null");
        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(codec != null, "codec must not be null");
        this.recordStore = recordStore;
        this.timingNodeId = timingNodeId;
        this.codec = codec;
    }

    @Override
    public LoadResult load() throws PersistenceException {
        final AppendOnlyRecordStore.LoadResult raw;
        try {
            raw = recordStore.load();
        } catch (AppendOnlyRecordStore.StoreException ex) {
            throw new PersistenceException("could not load TimingData records", ex);
        }

        List<TimingData> records = new ArrayList<>(raw.records().size());
        int recordNumber = 1;
        for (AppendOnlyRecordStore.Record record : raw.records()) {
            TimingData data = decode(record.payload(), recordNumber);
            validateStreamRecord(data, records.size() + 1L, recordNumber);
            records.add(data);
            recordNumber++;
        }

        return new LoadResult(records, raw.repairedIncompleteTail());
    }

    @Override
    public void append(TimingData data) throws PersistenceException {
        checkArgument(data != null, "data must not be null");
        if (!timingNodeId.equals(data.timingNodeId())) {
            throw new PersistenceException(
                    "TimingData belongs to " + data.timingNodeId()
                            + " but persistence owns " + timingNodeId);
        }

        final byte[] payload;
        try {
            payload = codec.encode(data);
        } catch (TimingDataCodec.CodecException ex) {
            throw new PersistenceException(
                    "could not encode TimingData for append",
                    ex);
        }

        try {
            recordStore.append(payload);
        } catch (AppendOnlyRecordStore.StoreException ex) {
            throw new PersistenceException(
                    "could not append TimingData record",
                    ex);
        }
    }

    private TimingData decode(byte[] payload, int recordNumber)
            throws PersistenceException {
        try {
            return codec.decode(payload);
        } catch (TimingDataCodec.CodecException ex) {
            throw new PersistenceException(
                    "invalid TimingData record " + recordNumber,
                    ex);
        }
    }

    private void validateStreamRecord(
            TimingData data,
            long expectedSequence,
            int recordNumber)
            throws PersistenceException {
        if (!timingNodeId.equals(data.timingNodeId())) {
            throw new PersistenceException(
                    "TimingData record " + recordNumber + " belongs to "
                            + data.timingNodeId()
                            + " but persistence owns " + timingNodeId);
        }
        if (data.sequenceNumber() != expectedSequence) {
            throw new PersistenceException(
                    "TimingData record " + recordNumber + " sequence must be "
                            + expectedSequence + " but was "
                            + data.sequenceNumber());
        }
    }
}
