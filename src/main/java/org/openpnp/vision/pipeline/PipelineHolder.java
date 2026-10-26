package org.openpnp.vision.pipeline;

import org.openpnp.spi.Camera;

/**
 * Something that runs a vision pipeline. It owns the pipeline and knows the context a real run
 * gives it, so a pipeline tried out elsewhere (an editor, the MCP server) behaves as it will in a
 * job.
 */
public interface PipelineHolder {
    /** The pipeline this holder runs; edits to it are saved with the holder. */
    CvPipeline getPipeline();

    /** Sets the pipeline properties a real run sets before processing. */
    void preparePipeline(CvPipeline pipeline, Camera camera) throws Exception;
}
