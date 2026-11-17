package io.mosire.bma3.qqofficial.media;

/** Upload-stage state kept separate from the message delivery state. */
public enum OfficialQqMediaUploadState {
    NOT_STARTED,
    UPLOADED,
    UPLOAD_FAILED,
    UPLOAD_UNKNOWN
}
