package com.github.topi314.lavasrc.ripsrc;

import com.github.topi314.lavasrc.ExtendedAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;

public class RipSrcAudioTrack extends ExtendedAudioTrack {
	private final RipSrcAudioSourceManager sourceManager;

	public RipSrcAudioTrack(AudioTrackInfo trackInfo, RipSrcAudioSourceManager sourceManager) {
		this(trackInfo, null, null, null, null, sourceManager);
	}

	public RipSrcAudioTrack(AudioTrackInfo trackInfo, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, RipSrcAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, null, false);
		this.sourceManager = sourceManager;
	}

	private URI getTrackMediaURI() throws Exception {
		// The URL is already provided in the trackInfo.uri from the API response
		return new URI(this.trackInfo.uri);
	}

	@Override
	public void process(LocalAudioTrackExecutor executor) throws Exception {
		try (var httpInterface = this.sourceManager.getHttpInterface()) {
			var downloadLink = this.getTrackMediaURI();
			try (var stream = new PersistentHttpStream(httpInterface, downloadLink, this.trackInfo.length)) {
				InternalAudioTrack track;

				// Determine audio format based on codec in URL
				var downloadUrl = downloadLink.toString();
				if (downloadUrl.contains("&codec=mp4a") || downloadUrl.contains("codec=mp4a")) {
					track = new MpegAudioTrack(this.trackInfo, stream);
				} else {
					// Default to Matroska for webm and other formats
					track = new MatroskaAudioTrack(this.trackInfo, stream);
				}

				processDelegate(track, executor);
			}
		}
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new RipSrcAudioTrack(this.trackInfo, this.albumName, this.albumUrl, this.artistUrl, this.artistArtworkUrl, this.sourceManager);
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.sourceManager;
	}
}
