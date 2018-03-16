/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.core.endpoint.kv;

/**
 * Enum describing all known response status codes that could be seen on the KeyValue protocol.
 *
 * Based on include/memcached/protocol_binary.h from memcached repository.
 *
 * @author Sergey Avseyev
 * @author Michael Nitschinger
 * @since 1.2.0
 * @see com.couchbase.client.core.message.ResponseStatus
 * @see com.couchbase.client.core.endpoint.ResponseStatusConverter
 */
public enum KeyValueStatus {

    /* This value describes unmatched code */
    UNKNOWN((short) -1, "Unknown code (dummy value)"),

    SUCCESS((short) 0x00,
            "The operation completed successfully"),
    ERR_NOT_FOUND((short) 0x01,
            "The key does not exists"),
    ERR_EXISTS((short) 0x02,
            "The key exists in the cluster (with another CAS value)"),
    ERR_TOO_BIG((short) 0x03,
            "The document exceeds the maximum size"),
    ERR_INVALID((short) 0x04,
            "Invalid request"),
    ERR_NOT_STORED((short) 0x05,
            "The document was not stored for some reason"),
    ERR_DELTA_BADVAL((short) 0x06,
            "Non-numeric server-side value for incr or decr"),
    ERR_NOT_MY_VBUCKET((short) 0x07,
            "The server is not responsible for the requested vbucket"),
    ERR_NO_BUCKET((short) 0x08,
            "Not connected to a bucket"),
    ERR_AUTH_STALE((short) 0x1f,
            "The authentication context is stale, reauthentication should be performed"),
    ERR_AUTH_ERROR((short) 0x20,
            "Authentication failure"),
    ERR_AUTH_CONTINUE((short) 0x21,
            "Authentication OK, but further action needed"),
    ERR_RANGE((short) 0x22,
            "The requested value is outside the legal range"),
    ERR_ROLLBACK((short) 0x23,
            "Roll back to an earlier version of the vbucket UUID"),
    ERR_ACCESS((short) 0x24,
            "No access"),
    ERR_NOT_INITIALIZED((short) 0x25,
            "The server is currently initializing this node"),
    ERR_UNKNOWN_COMMAND((short) 0x81,
            "The server does not know this command"),
    ERR_NO_MEM((short) 0x82,
            "Not enough memory"),
    ERR_NOT_SUPPORTED((short) 0x83,
            "The server does not support this command"),
    ERR_INTERNAL((short) 0x84,
            "An internal error in the server"),
    ERR_BUSY((short) 0x85,
            "The system is currently too busy to handle the request"),
    ERR_TEMP_FAIL((short) 0x86,
            "A temporary error condition occurred. Retrying the operation may resolve the problem."),

    /* Sub-document specific responses */
    ERR_SUBDOC_PATH_NOT_FOUND((short) 0xc0,
            "The provided path does not exist in the document"),
    ERR_SUBDOC_PATH_MISMATCH((short) 0xc1,
            "One of path components treats a non-dictionary as a dictionary, or a non-array as an array, or value the path points to is not a number"),
    ERR_SUBDOC_PATH_INVALID((short) 0xc2,
            "The path's syntax was incorrect"),
    ERR_SUBDOC_PATH_TOO_BIG((short) 0xc3,
            "The path provided is too large: either the string is too long, or it contains too many components"),
    ERR_SUBDOC_DOC_TOO_DEEP((short) 0xc4,
            "The document has too many levels to parse"),
    ERR_SUBDOC_VALUE_CANTINSERT((short) 0xc5,
            "The value provided will invalidate the JSON if inserted"),
    ERR_SUBDOC_DOC_NOT_JSON((short) 0xc6,
            "The existing document is not valid JSON"),
    ERR_SUBDOC_NUM_RANGE((short) 0xc7,
            "The existing number is out of the valid range for arithmetic operations"),
    ERR_SUBDOC_DELTA_RANGE((short) 0xc8,
            "The operation would result in a number outside the valid range"),
    ERR_SUBDOC_PATH_EXISTS((short) 0xc9,
            "The requested operation requires the path to not already exist, but it exists"),
    ERR_SUBDOC_VALUE_TOO_DEEP((short) 0xca,
            "Inserting the value would cause the document to be too deep"),
    ERR_SUBDOC_INVALID_COMBO((short) 0xcb,
            "An invalid combination of commands was specified"),
    ERR_SUBDOC_MULTI_PATH_FAILURE((short) 0xcc,
            "Specified key was successfully found, but one or more path operations failed"),
    ERR_SUBDOC_XATTR_INVALID_FLAG_COMBO((short)  0xce,
            "An invalid combination of operations, using macros when not using extended attributes"),
    ERR_SUBDOC_XATTR_INVALID_KEY_COMBO((short) 0xcf,
            "Only single xattr key may be accessed at the same time"),
    ERR_SUBDOC_XATTR_UNKNOWN_MACRO((short) 0xd0,
            "The server has no knowledge of the requested macro"),
    SUBDOC_SUCCESS_DELETED_DOCUMENT((short)0xcd,
            "The subdoc operation completed successfully on the deleted document");

    private final short code;
    private final String description;

    KeyValueStatus(short code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Determine the right {@link KeyValueStatus} for the given status code.
     *
     * Certain status codes are checked upfront since they are most commonly converted (this avoids iterating
     * through the full enum values list, especially in the non-corner or failure case variants).
     *
     * @param code the status code to check.
     * @return the matched code, or unknown if none is found.
     */
    public static KeyValueStatus valueOf(final short code) {
        if (code == SUCCESS.code) {
            return SUCCESS;
        } else if (code == ERR_NOT_FOUND.code) {
            return ERR_NOT_FOUND;
        } else if (code == ERR_EXISTS.code) {
            return ERR_EXISTS;
        } else if (code == ERR_NOT_MY_VBUCKET.code) {
            return ERR_NOT_MY_VBUCKET;
        } else if (code == SUBDOC_SUCCESS_DELETED_DOCUMENT.code) {
            return SUCCESS;
        }

        for (KeyValueStatus keyValueStatus : values()) {
            if (keyValueStatus.code() == code) {
                return keyValueStatus;
            }
        }
        return UNKNOWN;
    }

    public short code() {
        return code;
    }

    public String description() {
        return description;
    }
}
