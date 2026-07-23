package com.melomaniac.app.util

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
