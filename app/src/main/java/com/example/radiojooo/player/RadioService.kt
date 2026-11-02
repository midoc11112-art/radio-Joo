    private fun startStream(streamUrl: String, useProxy: Boolean = false) {
        try {
            player.stop()
            player.clearMediaItems()
            isUsingProxy = useProxy

            // ✅ استخدام الـ proxy دايماً
            val finalUrl = if (useProxy) {
                "https://radio-joo-proxy.midoc11112.workers.dev/?url=" + 
                    URLEncoder.encode(streamUrl, "UTF-8")
            } else {
                streamUrl
            }
            
            val uri = Uri.parse(finalUrl)

            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                streamUrl.contains(".aac", ignoreCase = true) -> MimeTypes.AUDIO_AAC
                else -> MimeTypes.AUDIO_MPEG
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(currentTitle)
                .setArtist(currentCountry)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .build()

            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            val filteredSource = FilteringMediaSource(mediaSource, C.TRACK_TYPE_AUDIO)

            player.setMediaSource(filteredSource)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
