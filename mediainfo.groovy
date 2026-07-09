#!/usr/bin/env -S filebot -script


// raw / slow mode (i.e. use libmediainfo and write xattr)
if (_args.mode == /raw/) {
	log.finest "# ${MediaInfo.version()}"

	def mediaFiles = args.files.findAll{ f -> f.video || f.audio }
	if (mediaFiles.size() == 0) {
		die "Invalid usage: no media files: $args"
	}

	// reset cache to force xattr reads
	def cache = Cache.getCache('mediainfo', CacheType.Monthly)
	if (cache.keys) {
		help "[CLEAR] ${cache} cache (${cache.keys.size()})"
		cache.clear()
	}

	def progress = [count: 0, read: 0, size: 0]

	return mediaFiles.each{ f ->
		try(def mi = new MediaInfo()) {
			def read = mi.read(f, 8192)
			def raw = mi.raw()

			// print stats
			log.fine "\n# $f (${read.displaySize} of ${f.displaySize})"
			log.info "\n$raw"

			// minify and write to xattr
			if (raw) {
				f.xattr['net.filebot.mediainfo'] = raw.split(/\R+/).findResults{ it.replaceFirst(/[ ]+[:][ ]+/, '\t') }.join('\n')
				f.xattr['net.filebot.mediainfo.mtime'] = f.lastModified() as String
			}

			log.finest "# Files Read: ${progress.count += 1} / ${mediaFiles.size()}\n# Bytes Read: ${(progress.read += read).displaySize} / ${(progress.size += f.length()).displaySize}\n"
		}
	}
}




// default / fast mode (i.e. use local cache or xattr or libmediainfo)
args.files.each{ f ->
	log.fine "\n# $f"
	try {
		f.mediaInfo.each{ kind ->
			kind.each{ stream ->
				log.finest "\n[${kind}]"

				// find optimal padding
				def pad = stream.keySet().flatten().collect{ it.length() }.max()
				stream.each{ k,v ->
					log.info "${k.padRight(pad)} : $v"
				}
			}
		}
	} catch (error) {
		log.severe "$error.message"
	}
}
