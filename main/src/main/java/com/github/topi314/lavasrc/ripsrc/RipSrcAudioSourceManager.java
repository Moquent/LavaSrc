package com.github.topi314.lavasrc.ripsrc;

import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.topi314.lavasrc.ExtendedAudioSourceManager;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class RipSrcAudioSourceManager extends ExtendedAudioSourceManager implements HttpConfigurable, AudioSearchManager {

	public static final String SEARCH_PREFIX = "ripsearch:";
	public static final String ISRC_PREFIX = "ripisrc:";
	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.TRACK);

	private static final Logger log = LoggerFactory.getLogger(RipSrcAudioSourceManager.class);

	private final HttpInterfaceManager httpInterfaceManager;
	private final String apiKey;
	private final String baseUrl;
	private final String sourceName;
	private final String userAgent;
	private final boolean external;

	public RipSrcAudioSourceManager(String apiKey, String baseUrl, String sourceName, String userAgent, boolean external) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.sourceName = sourceName != null ? sourceName : "ripsrc";
		this.userAgent = userAgent != null ? userAgent : "LavaSrc";
		this.external = external;
		this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	}

	@NotNull
	@Override
	public String getSourceName() {
		return this.sourceName;
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new RipSrcAudioTrack(trackInfo,
			extendedAudioTrackInfo.albumName,
			extendedAudioTrackInfo.albumUrl,
			extendedAudioTrackInfo.artistUrl,
			extendedAudioTrackInfo.artistArtworkUrl,
			this);
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		var identifier = reference.identifier;

		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(identifier.substring(SEARCH_PREFIX.length()));
			}
			if (identifier.startsWith(ISRC_PREFIX)) {
				return this.getTrackByISRC(identifier.substring(ISRC_PREFIX.length()));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	@Override
	@Nullable
	public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		if (!types.isEmpty() && !types.stream().allMatch(type -> type.equals(AudioSearchResult.Type.TRACK))) {
			throw new RuntimeException(getSourceName() + " can only search tracks");
		}
		try {
			if (query.startsWith(SEARCH_PREFIX)) {
				return getSearchResults(query.substring(SEARCH_PREFIX.length()));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	private AudioSearchResult getSearchResults(String query) throws IOException {
		var url = this.baseUrl + "?p=" + this.apiKey + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		var json = this.getJson(url);
		var tracks = json == null || json.values().isEmpty() ? new ArrayList<AudioTrack>() : this.parseTracks(json);
		return new BasicAudioSearchResult(tracks, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
	}

	public JsonBrowser getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.setHeader("Accept", "application/json");
		request.setHeader("User-Agent", this.userAgent);
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private AudioItem getSearch(String query) throws IOException {
		var url = this.baseUrl + "?p=" + this.apiKey + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		var json = this.getJson(url);
		if (json == null || json.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new BasicAudioPlaylist("RipSrc Search: " + query, this.parseTracks(json), null, true);
	}

	private AudioItem getTrackByISRC(String isrc) throws IOException {
		var url = this.baseUrl + "?p=" + this.apiKey + "&isrcs=" + URLEncoder.encode(isrc, StandardCharsets.UTF_8) + "&external=" + this.external;
		var json = this.getJson(url);
		if (json == null || json.index(0).get("id").isNull()) {
			return AudioReference.NO_TRACK;
		}
		return this.parseTrack(json.index(0));
	}

	private List<AudioTrack> parseTracks(JsonBrowser json) {
		var tracks = new ArrayList<AudioTrack>();
		for (var track : json.values()) {
			tracks.add(this.parseTrack(track));
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonBrowser json) {
		var id = json.get("id").text();
		var title = json.get("title").text();
		var artist = json.get("artist").text();
		var duration = json.get("duration").asLong(0) * 1000; // Convert to milliseconds
		var artworkUrl = json.get("picture").text();
		var isrc = json.get("isrc").index(0).isNull() ? null : json.get("isrc").index(0).text();
		
		// Build the download URL with codec information
		var downloadUrl = json.get("versions").index(0).get("url").text() + 
			"&codec=" + json.get("versions").index(0).get("codec").text();

		var trackInfo = new AudioTrackInfo(title, artist, duration, id, false, downloadUrl, artworkUrl, isrc);
		return new RipSrcAudioTrack(trackInfo, this);
	}

	public HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}

	@Override
	public boolean isTrackEncodable(AudioTrack track) {
		return true;
	}

	@Override
	public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
		// No additional encoding needed
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}
}
