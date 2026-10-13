package com.wenotes.app;

import java.io.IOException;

final class UpdateException extends IOException {
    final String kind;
    UpdateException(String kind) { super(kind); this.kind = kind; }
}
