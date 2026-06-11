htpc = [
	kodi: [ http: 8080 ],
	plex: [ http: 32400],
	emby: [ http: 8096, https: 8920 ]
]


/**
 * Kodi helper functions
 */
def scanVideoLibrary(host, port) {
	def json = [jsonrpc: '2.0', method: 'VideoLibrary.Scan', id: 1]
	postKodiRPC(host, port, json)
}

def showNotification(host, port, title, message, image) {
	def json = [jsonrpc:'2.0', method:'GUI.ShowNotification', params: [title: title, message: message, image: image], id: 1]
	postKodiRPC(host, port, json)
}

def postKodiRPC(host, port, json) {
	def url = "http://$host:${port ?: htpc.kodi.http}/jsonrpc"
	def data = JsonOutput.toJson(json)

	log.finest "POST: $url $data"
	new URL(url).post(data.getBytes('UTF-8'), 'application/json', [:])
}



/**
 * Plex helpers
 */
def encodeQueryString(parameters) {
	def query = parameters.findAll{ k, v -> k && v }.collect{ k, v -> k + '=' + URLEncoder.encode(v, 'UTF-8') }.join('&')
	return query ? '?' + query : ''
}

def refreshPlexLibrary(server, port, token, files) {
	// use HTTPS if hostname is specified, use HTTP if IP is specified
	def protocol = server ==~ /localhost|[0-9.:]+/ ? 'http' : 'https'
	def endpoint = "${protocol}://${server}:${port ?: htpc.plex.http}/library/sections"
	// pass authentication token via query parameters
	def auth = ['X-Plex-Token': token]

	// try to narrow down the rescan request to a specific library and folder path
	def requests = [] as SortedSet
	if (files) {
		// request remote library information
		def libraryRoot = [:].withDefault{ [] }
		def xml = new XmlSlurper().parse(endpoint + encodeQueryString(auth))
		xml.'Directory'.'Location'.collect{ location ->
			def key = location.'..'.'@key'.text()
			def path = location.'@path'.text()
			def root = path.split(/[\\\/]/).last()
			libraryRoot[root] += [key: key, path: path]
		}

		def folders = files.findResults{ f -> f.dir } as SortedSet
		folders.collect{ f -> f.path.split(/[\\\/]/).tail() }.each{ components ->
			components.eachWithIndex{ c, i ->
				libraryRoot[c].each{ r ->
					def sectionKey = r.key
					def remotePath = r.path
					// scan specific library path
					if (i < components.size() - 1) {
						remotePath += '/' + components[i+1..-1].join('/')
					}
					requests += endpoint + '/' + sectionKey + '/refresh' + encodeQueryString(path: remotePath, *: auth)
				}
			}
		}
	}

	// refresh all libraries as a last resort
	if (requests.empty) {
		requests += endpoint + '/all/refresh' + encodeQueryString(auth)
	}

	requests.each{ url ->
		log.finest "GET: $url"
		new URL(url).get()
	}
}


/**
 * Jellyfin helpers
 */
def refreshJellyfinLibrary(server, port, token) {
	// use HTTPS if hostname is specified, use HTTP if IP is specified
	def protocol = server ==~ /localhost|[0-9.:]+/ ? 'http' : 'https'
	def url = "${protocol}://${server}:${port ?: htpc.emby[protocol]}/Library/Refresh"
	if (token) {
		url += "?api_key=$token"
	}
	log.finest "POST: $url"
	new URL(url).post([:], [:])
}


/**
 * TheTVDB artwork/nfo helpers
 */
def fetchSeriesBanner(outputFile, series, bannerType, bannerType2, season, override, locale) {
	if (outputFile.exists() && !override) {
		return outputFile
	}

	def artwork = series.getArtwork(bannerType, locale)
	if (artwork == null) {
		return null
	}

	def banner = artwork.find{ it.matches(bannerType2, season) }
	if (banner == null) {
		return null
	}

	log.finest "Fetching $outputFile => $banner"
	return banner.url.cache().saveAs(outputFile)
}

def fetchSeriesFanart(outputFile, series, type, season, override, locale) {
	if (outputFile.exists() && !override) {
		return outputFile
	}

	def artwork = FanartTV.getArtwork(series.id, "tv", locale)
	def fanart = artwork.find{ it.matches(type, season) }
	if (fanart == null) {
		return null
	}

	log.finest "Fetching $outputFile => $fanart"
	return fanart.url.cache().saveAs(outputFile)
}

def fetchSeriesNfo(outputFile, s, locale) {
	// generate nfo file
	log.finest "Generate Series NFO: $s.name [$s]"
	def db = s.database.match('TheMovieDB':'tmdb', 'TheTVDB':'tvdb', 'AniDB':'anidb', 'TVmaze':'tvmaze')
	def xml = XML {
		tvshow {
			id(s.id)
			title(s.name)
			sorttitle([s.name, s.startDate].findResults{ it?.toString()?.sortName() }.join(' :: '))
			year(s.startDate?.year)
			premiered(s.startDate)
			mpaa(s.certification)
			plot(s.overview)
			runtime(s.runtime)

			ratings {
				rating(name: db, max: '10', default: 'true') {
					value(s.rating)
					votes(s.ratingCount)
				}
			}

			status(s.status)
			studio(s.network)

			episodeguide(s.id)

			s.episodes.collectEntries{ e -> [e.episode ? e.season : 0, e.group] }.each{ seasonNumber, seasonName ->
				if (seasonName) {
					namedseason(number: seasonNumber, seasonName)
				}
			}

			s.genres.each{ g ->
				genre(g)
			}
			s.country.each{ c ->
				country(c)
			}

			s.artwork.findAll{ a -> a.matches(/posters/) }.take(1).each{ a ->
				thumb(aspect: 'poster', a.url)
			}
			s.artwork.findAll{ a -> a.matches(/logos/) }.take(1).each{ a ->
				thumb(aspect: 'clearlogo', a.url)
			}
			s.artwork.findAll{ a -> a.matches(/backdrops/) }.take(1).each{ a ->
				fanart {
					thumb(a.url)
				}
			}

			s.certifications.each{ k, v ->
				certification {
					country(k)
					rating(v)
				}
			}

			s.crew.each{ p ->
				if (p.actor) {
					actor {
						name(p.name)
						if (p.character) {
							role(p.character)
						}
						if (Settings.ApplicationRevisionNumber > 10960) {
							if (p.order >= 0) {
								order(p.order)
							}
						}
						if (p.image) {
							thumb(p.image)
						}
					}
				} else if (p.director) {
					director(p.name)
				} else if (p.writer || p.department == 'Writing') {
					credits(p.name)
				}
			}

			if (s.database =~ /TheMovieDB/) {
				tmdb(id: s.id, 'https://www.themoviedb.org/tv/' + s.id)
			}
			if (s.database =~ /TheTVDB/) {
				tvdb(id: s.id, 'https://thetvdb.com/series/' + s.slug)
			}
			if (s.database =~ /AniDB/) {
				anidb(id: s.id, 'https://anidb.net/anime/' + s.id)
			}

			uniqueid(type: db, default: 'true', s.id)
		}
	}

	xml.saveAs(outputFile)
}

def getTVDBID(series, locale) {
	def sid = series.getExternalId(/TheTVDB/)
	if (sid) {
		return TheTVDB.getSeriesInfo(sid, locale)
	}
	return null
}

def fetchSeriesArtworkAndNfo(seriesDir, seasonDir, series, season, override = false, locale = Locale.ENGLISH) {
	tryLogCatch {
		def details = series.details
		if (details == null) {
			log.finest "NFO not supported: $series"
			return
		}

		// fetch nfo
		fetchSeriesNfo(seriesDir.resolve('tvshow.nfo'), details, locale)

		// primary poster as folder image
		fetchPrimaryPoster(details.poster, seriesDir.resolve('folder.jpg'))

		// artwork types are mostly sourced from TheTVDB
		def tvdbid = getTVDBID(series, locale)
		if (tvdbid == null) {
			log.finest "Artwork not supported: $series"
			return
		}

		// series artwork
		fetchSeriesBanner(seriesDir.resolve('poster.jpg'), tvdbid, 'posters', 'series', null, override, locale)
		fetchSeriesBanner(seriesDir.resolve('banner.jpg'), tvdbid, 'banners', 'series', null, override, locale)
		fetchSeriesBanner(seriesDir.resolve('fanart.jpg'), tvdbid, 'backgrounds', 'series', null, override, locale)

		// season artwork
		if (seasonDir != seriesDir) {
			fetchSeriesBanner(seasonDir.resolve('folder.jpg'), tvdbid, 'posters', 'season', season, override, locale)
			fetchSeriesBanner(seasonDir.resolve('poster.jpg'), tvdbid, 'posters', 'season', season, override, locale)
			fetchSeriesBanner(seasonDir.resolve('banner.jpg'), tvdbid, 'banners', 'season', season, override, locale)
		}

		// external series artwork
		['hdclearart', 'clearart'].findResult{ type -> fetchSeriesFanart(seriesDir.resolve('clearart.png'), tvdbid, type, null, override, locale) }
		['hdtvlogo', 'clearlogo'].findResult{ type -> fetchSeriesFanart(seriesDir.resolve('logo.png'), tvdbid, type, null, override, locale) }
		fetchSeriesFanart(seriesDir.resolve('landscape.jpg'), tvdbid, 'tvthumb', null, override, locale)

		// external season artwork
		if (seasonDir != seriesDir) {
			fetchSeriesFanart(seasonDir.resolve('landscape.jpg'), tvdbid, 'seasonthumb', season, override, locale)
		}
	}
}



/**
 * TheMovieDB artwork/nfo helpers
 */
def fetchMovieArtwork(outputFile, movieInfo, category, override, locale) {
	if (outputFile.exists() && !override) {
		log.finest "Artwork already exists: $outputFile"
		return outputFile
	}

	// select and fetch artwork
	def artwork = TheMovieDB.getArtwork(movieInfo.id, category, locale)
	def selection = artwork[0]
	if (selection == null) {
		log.finest "Artwork not found: $outputFile"
		return null
	}
	log.finest "Fetching $outputFile => $selection"
	return selection.url.cache().saveAs(outputFile)
}

def fetchMovieFanart(outputFile, movieInfo, type, diskType, override, locale) {
	if (outputFile.exists() && !override) {
		log.finest "Fanart already exists: $outputFile"
		return outputFile
	}

	def artwork = FanartTV.getArtwork(movieInfo.id, "movies", locale)
	def fanart = artwork.find{ it.matches(type, diskType) }
	if (fanart == null) {
		log.finest "Fanart not found: $outputFile / $type"
		return null
	}
	log.finest "Fetching $outputFile => $fanart"
	return fanart.url.cache().saveAs(outputFile)
}

def fetchMovieNfo(outputFile, m, i, movieFile) {
	log.finest "Generate Movie NFO: $i.name [$i.id]"
	def mi = tryLogCatch{ movieFile?.mediaInfo }
	def xml = XML {
		movie {
			id(i.id)
			title(i.name)
			originaltitle(i.originalName)
			sorttitle((i.collection && i.released ? [i.collection, i.released, i.name] : [i.name, i.released]).findResults{ it?.toString()?.sortName() }.join(' :: '))
			year(i.released?.year)
			premiered(i.released)
			mpaa(i.certification)
			plot(i.overview)
			tagline(i.tagline)
			runtime(i.runtime)
			status(i.status)

			ratings {
				rating(name: 'tmdb', max: '10', default: 'true') {
					value(i.rating)
					votes(i.votes)
				}
			}

			if (Settings.ApplicationRevisionNumber > 10960) {
				if (i.collection) {
					set(id: m.collection.id) {
						name(m.collection.name)
						overview(m.collection.overview)
					}
				}
			}

			i.genres.each{ g ->
				genre(g)
			}
			i.keywords.each{ k ->
				tag(k)
			}
			i.productionCountries.each{ c ->
				country(c)
			}
			i.productionCompanies.each{ c ->
				studio(c)
			}

			m.artwork.findAll{ a -> a.matches(/posters/) }.take(1).each{ a ->
				thumb(aspect: 'poster', a.url)
			}

			m.artwork.findAll{ a -> a.matches(/backdrops/) }.take(1).each{ a ->
				fanart {
					thumb(a.url)
				}
			}

			i.certifications.each{ k, v ->
				certification {
					country(k)
					rating(v)
				}
			}

			i.crew.each{ p ->
				if (p.actor) {
					actor {
						name(p.name)
						if (p.character) {
							role(p.character)
						}
						if (Settings.ApplicationRevisionNumber > 10960) {
							if (p.order >= 0) {
								order(p.order)
							}
						}
						if (p.image) {
							thumb(p.image)
						}
					}
				} else if (p.director) {
					director(p.name)
				} else if (p.writer || p.department == 'Writing') {
					credits(p.name)
				}
			}

			fileinfo {
				name(movieFile?.name)
				size(movieFile?.length())

				streamdetails {
					mi?.Video.each{ s ->
						video {
							codec(s.'Encoded_Library/Name' ?: s.'CodecID/Hint' ?: s.'Format')
							aspect(s.'DisplayAspectRatio/String')
							width(s.'Width')
							height(s.'Height')
							hdrtype(s.'HDR_Format_Commercial' ?: s.'HDR_Format')
							framerate(s.'FrameRate')
							bitrate(s.'BitRate')
							duration(s.'Duration'.toFloat().div(60000).round(4))
						}
					}
					mi?.Audio.each{ s ->
						audio {
							codec(s.'CodecID/Hint' ?: s.'Format')
							language(s.'Language/String3')
							channels(s.'Channel(s)_Original' ?: s.'Channel(s)')
							bitrate(s.'BitRate')
						}
					}
					mi?.Text.each{ s ->
						subtitle {
							codec(s.'Format')
							language(s.'Language/String3')
						}
					}
				}
			}

			if (i.imdbId) {
				imdb(id: 'tt' + i.imdbId.pad(7), 'https://www.imdb.com/title/tt' + i.imdbId.pad(7))
			}
			tmdb(id: i.id, 'https://www.themoviedb.org/movie/' + i.id)
			uniqueid(type: 'tmdb', default: 'true', i.id)
		}
	}
	xml.saveAs(outputFile)
}

def fetchMovieArtworkAndNfo(movieDir, movie, movieFile = null, override = false, locale = Locale.ENGLISH) {
	tryLogCatch {
		def movieInfo = TheMovieDB.getMovieInfo(movie, locale, true)

		// fetch nfo
		fetchMovieNfo(movieDir.resolve('movie.nfo'), movie, movieInfo, movieFile)

		// primary poster as folder image
		fetchPrimaryPoster(movieInfo.poster, movieDir.resolve('folder.jpg'))

		// fetch series banner, fanart, posters, etc
		fetchMovieArtwork(movieDir.resolve('poster.jpg'), movieInfo, 'posters', override, locale)
		fetchMovieArtwork(movieDir.resolve('fanart.jpg'), movieInfo, 'backdrops', override, Locale.ROOT) // prefer no language backdrops

		['hdmovieclearart', 'movieart'].findResult { type -> fetchMovieFanart(movieDir.resolve('clearart.png'), movieInfo, type, null, override, locale) }
		['hdmovielogo', 'movielogo'].findResult { type -> fetchMovieFanart(movieDir.resolve('logo.png'), movieInfo, type, null, override, locale) }
		['bluray', 'dvd', null].findResult { diskType -> fetchMovieFanart(movieDir.resolve('disc.png'), movieInfo, 'moviedisc', diskType, override, locale) }
	}
}

def fetchPrimaryPoster(url, file) {
	if (url && !file.exists()) {
		url.cache().saveAs(file)
	}
}
