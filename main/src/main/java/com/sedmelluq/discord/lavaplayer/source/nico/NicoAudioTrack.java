package com.sedmelluq.discord.lavaplayer.source.nico;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Audio track that handles processing NicoNico tracks.
 */
public class NicoAudioTrack extends DelegatedAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(NicoAudioTrack.class);

    private String actionTrackId;

    private final NicoAudioSourceManager sourceManager;

    /**
     * @param trackInfo     Track info
     * @param sourceManager Source manager which was used to find this track
     */
    public NicoAudioTrack(AudioTrackInfo trackInfo, NicoAudioSourceManager sourceManager) {
        super(trackInfo);

        // randomize the actionTrackId to prevent any issues with tracking IDs becoming invalid.
        actionTrackId = "Lavaplayer_" + System.currentTimeMillis();
        this.sourceManager = sourceManager;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            String playbackUrl = loadPlaybackUrl(httpInterface);

            log.debug("Starting NicoNico track from URL: {}", playbackUrl);

            
            processDelegate(
                new HlsStreamTrack(trackInfo, extractHlsAudioPlaylistUrl(httpInterface, playbackUrl), sourceManager.getHttpInterfaceManager(), true),
                localExecutor
            );
        }
    }

    /**
     * DMS provides a separate video and audio stream. This extracts the audio playlist URL from EXT-X-MEDIA, same as Vimeo.
     **/
    private String extractHlsAudioPlaylistUrl(HttpInterface httpInterface, String videoPlaylistUrl) throws IOException {
        String url = null;
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(videoPlaylistUrl))) {
            int statusCode = response.getStatusLine().getStatusCode();

            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                throw new FriendlyException("Server responded with an error.", SUSPICIOUS,
                    new IllegalStateException("Response code for track access info is " + statusCode));
            }

            String bodyString = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            for (String rawLine : bodyString.split("\n")) {
                ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(rawLine);

                if (Objects.equals(line.directiveName, "EXT-X-MEDIA") && Objects.equals(line.directiveArguments.get("TYPE"), "AUDIO")) {
                    url = line.directiveArguments.get("URI");
                    break;
                }
            }
        }

        if (url == null) {
            throw new FriendlyException("Failed to find audio playlist URL.", SUSPICIOUS,
                new IllegalStateException("Valid audio directive was not found"));
        }

        return url;
    }

    private JsonBrowser loadVideoApi(HttpInterface httpInterface) throws IOException {
        String apiUrl = "https://www.nicovideo.jp/api/watch/v3_guest/" + getIdentifier() + "?_frontendId=6&_frontendVersion=0&actionTrackId=" + actionTrackId + "&i18nLanguage=en-us";

        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(apiUrl))) {
            HttpClientTools.assertSuccessWithContent(response, "api response");

            return JsonBrowser.parse(response.getEntity().getContent()).get("data");
        }
    }

    private JsonBrowser loadVideoMainPage(HttpInterface httpInterface) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(trackInfo.uri))) {
            HttpClientTools.assertSuccessWithContent(response, "video main page");

            String urlEncodedData = DataFormatTools.extractBetween(EntityUtils.toString(response.getEntity()), "data-api-data=\"", "\"");
            String watchData = Parser.unescapeEntities(urlEncodedData, false);

            return JsonBrowser.parse(watchData);
        }
    }

    private String loadPlaybackUrl(HttpInterface httpInterface) throws IOException {
        JsonBrowser videoJson = loadVideoApi(httpInterface);

        if (videoJson.isNull()) {
            log.warn("Couldn't retrieve NicoNico video details from API, falling back to HTML page...");
            videoJson = loadVideoMainPage(httpInterface);
        }

        if (!videoJson.isNull()) {
            // an "actionTrackId" is necessary to receive an API response.
            // We make sure this is kept up to date to prevent any issues with tracking IDs becoming invalid.
            String trackingId = videoJson.get("client").get("watchTrackId").text();

            if (trackingId != null) {
                actionTrackId = trackingId;
            }
        }

        // yet to be implemented?
        String accessRightKey = videoJson.get("media").get("domand").get("accessRightKey").text();
        String audioQuality = videoJson.get("media").get("domand").get("audios").index(0).get("id").text();
        
        String apiUrl = " https://nvapi.nicovideo.jp/v1/watch/" + getIdentifier() + "/access-rights/hls?actionTrackId=" + actionTrackId;

        HttpPost request = new HttpPost(apiUrl);
        request.setHeader("X-Access-Right-Key", accessRightKey);
        request.setHeader("X-Frontend-Id", "6");
        request.setHeader("X-Frontend-Version", "0");
        request.setHeader("X-Requested-With", "https://www.nicovideo.jp");
        request.setEntity(new StringEntity("{\"outputs\":[[\"video-h264-144p\",\"" + audioQuality + "\"]]}"));


        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            HttpClientTools.assertSuccessWithContent(response, "dms response");

            JsonBrowser dmsResponse = JsonBrowser.parse(response.getEntity().getContent());
            
            return dmsResponse.get("data").get("contentUrl").text();
        }

    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new NicoAudioTrack(trackInfo, sourceManager);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
