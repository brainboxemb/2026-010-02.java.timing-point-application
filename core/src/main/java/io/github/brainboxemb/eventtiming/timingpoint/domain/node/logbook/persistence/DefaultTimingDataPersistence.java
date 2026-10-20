package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.AppendOnlyRecordStore;

import java.util.ArrayList;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Scalar timing-record persistence over a generic append-only record store.
 */
public final class DefaultTimingDataPersistence implements TimingDataPersistence {
    private final AppendOnlyRecordStore recordStore;
    private final char timingNodeId;
    private final TimingDataCodec codec;

    public DefaultTimingDataPersistence(
            AppendOnlyRecordStore recordStore,
            NodeId timingNodeId,
            TimingDataCodec codec) {
        failIfInvalidArgument(recordStore != null, "recordStore must not be null");
        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        failIfInvalidArgument(codec != null, "codec must not be null");
        this.recordStore = recordStore;
        this.timingNodeId = timingNodeId.value().charAt(0);
        this.codec = codec;
    }

    @Override
    public LoadResult load() throws PersistenceException {
        final AppendOnlyRecordStore.LoadResult raw;
        try {
            raw = recordStore.load();
        } catch (AppendOnlyRecordStore.StoreException ex) {
            throw new PersistenceException("could not load timing records", ex);
        }

        List<TimingRecord> records = new ArrayList<TimingRecord>(raw.records().size());
        int recordNumber = 1;
        for (AppendOnlyRecordStore.Record rawRecord : raw.records()) {
            TimingRecord record = decode(rawRecord.payload(), recordNumber);
            validateStreamRecord(record, records.size() + 1L, recordNumber);
            records.add(record);
            recordNumber++;
        }
        return new LoadResult(records, raw.repairedIncompleteTail());
    }

    @Override
    public void append(TimingRecord record) throws PersistenceException {
        failIfInvalidArgument(record != null, "record must not be null");
        if (record.nodeId() != timingNodeId) {
            throw new PersistenceException(
                    "TimingRecord belongs to " + record.nodeId()
                            + " but persistence owns " + timingNodeId);
        }

        final byte[] payload;
        try {
            payload = codec.encode(record);
        } catch (TimingDataCodec.CodecException ex) {
            throw new PersistenceException("could not encode timing record for append", ex);
        }

        try {
            recordStore.append(payload);
        } catch (AppendOnlyRecordStore.StoreException ex) {
            throw new PersistenceException("could not append timing record", ex);
        }
    }

    private TimingRecord decode(byte[] payload, int recordNumber)
            throws PersistenceException {
        try {
            return codec.decodeRecord(payload);
        } catch (TimingDataCodec.CodecException ex) {
            throw new PersistenceException("invalid timing record " + recordNumber, ex);
        }
    }

    private void validateStreamRecord(
            TimingRecord record,
            long expectedSequence,
            int recordNumber)
            throws PersistenceException {
        if (record.nodeId() != timingNodeId) {
            throw new PersistenceException(
                    "Timing record " + recordNumber + " belongs to "
                            + record.nodeId() + " but persistence owns " + timingNodeId);
        }
        if (record.sequenceNumber() != expectedSequence) {
            throw new PersistenceException(
                    "Timing record " + recordNumber + " sequence must be "
                            + expectedSequence + " but was " + record.sequenceNumber());
        }
    }
}
