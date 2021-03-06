/*
 *  Copyright 2017 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package com.att.aro.core.packetanalysis.impl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.log4j.Level;
import org.codehaus.jackson.map.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

import com.att.aro.core.ILogger;
import com.att.aro.core.bestpractice.pojo.VideoUsage;
import com.att.aro.core.commandline.IExternalProcessRunner;
import com.att.aro.core.fileio.IFileManager;
import com.att.aro.core.model.InjectLogger;
import com.att.aro.core.packetanalysis.IHttpRequestResponseHelper;
import com.att.aro.core.packetanalysis.IVideoUsageAnalysis;
import com.att.aro.core.packetanalysis.pojo.AbstractTraceResult;
import com.att.aro.core.packetanalysis.pojo.ByteRange;
import com.att.aro.core.packetanalysis.pojo.HttpDirection;
import com.att.aro.core.packetanalysis.pojo.HttpRequestResponseInfo;
import com.att.aro.core.packetanalysis.pojo.PacketInfo;
import com.att.aro.core.packetanalysis.pojo.Session;
import com.att.aro.core.packetreader.pojo.Packet;
import com.att.aro.core.preferences.IPreferenceHandler;
import com.att.aro.core.preferences.impl.PreferenceHandlerImpl;
import com.att.aro.core.settings.Settings;
import com.att.aro.core.util.IStringParse;
import com.att.aro.core.util.StringParse;
import com.att.aro.core.util.Util;
import com.att.aro.core.videoanalysis.IVideoAnalysisConfigHelper;
import com.att.aro.core.videoanalysis.IVideoEventDataHelper;
import com.att.aro.core.videoanalysis.pojo.AROManifest;
import com.att.aro.core.videoanalysis.pojo.ManifestDash;
import com.att.aro.core.videoanalysis.pojo.ManifestHLS;
import com.att.aro.core.videoanalysis.pojo.VideoData;
import com.att.aro.core.videoanalysis.pojo.VideoEvent;
import com.att.aro.core.videoanalysis.pojo.VideoEvent.VideoType;
import com.att.aro.core.videoanalysis.pojo.VideoEventData;
import com.att.aro.core.videoanalysis.pojo.VideoUsagePrefs;
import com.att.aro.core.videoanalysis.pojo.config.VideoAnalysisConfig;

/**
 * Video usage analysis
 * 
 * @Barry
 */
public class VideoUsageAnalysisImpl implements IVideoUsageAnalysis {

	@Autowired
	private IFileManager filemanager;

	@Autowired
	private Settings settings;
	
	@InjectLogger
	private static ILogger log;

	private IExternalProcessRunner extrunner;

	@Autowired
	public void setExternalProcessRunner(IExternalProcessRunner runner) {
		this.extrunner = runner;
	}

	@Autowired
	private IVideoAnalysisConfigHelper voConfigHelper;

	@Autowired
	private IVideoEventDataHelper voEventDataHelper;

	@Autowired
	private IStringParse stringParse;

	private IHttpRequestResponseHelper reqhelper;
	
	private String tracePath;
	
	private boolean imageExtractionRequired = false;

	private String videoPath;
	
	private String imagePath;
	
	private boolean absTimeFlag = false;

	private TreeMap<Double, AROManifest> aroManifestMap;

	private AROManifest aroManifest = null;
	
	private final String fileVideoSegments = "video_segments";
	
	private String downloadPath;
	
	@Autowired
	public void setHttpRequestResponseHelper(IHttpRequestResponseHelper reqhelper) {
		this.reqhelper = reqhelper;
	}

	IPreferenceHandler prefs = PreferenceHandlerImpl.getInstance();

	private VideoUsagePrefs videoUsagePrefs;

	private double time0;

	private VideoAnalysisConfig vConfig;

	private TreeMap<Double, HttpRequestResponseInfo> reqMap;

	private VideoUsage videoUsage;
	
	/**
	 * Load VideoUsage Preferences
	 */
	public void loadPrefs() {
		ObjectMapper mapper = new ObjectMapper();

		String temp = prefs.getPref(VideoUsagePrefs.VIDEO_PREFERENCE);
		if (temp != null) {
			try {
				videoUsagePrefs = mapper.readValue(temp, VideoUsagePrefs.class);
			} catch (IOException e) {
				log.error("VideoUsagePrefs failed to de-serialize :" + e.getMessage());
			}
		} else {
			try {
				videoUsagePrefs = new VideoUsagePrefs();
				temp = mapper.writeValueAsString(videoUsagePrefs);
				prefs.setPref(VideoUsagePrefs.VIDEO_PREFERENCE, temp);
			} catch (IOException e) {
				log.error("VideoUsagePrefs failed to serialize :" + e.getMessage());
			}
		}
	}
	
	public IPreferenceHandler getPrefs() {
		return prefs;
	}

	public VideoUsagePrefs getVideoUsagePrefs() {
		return videoUsagePrefs;
	}

	@Override
	public VideoUsage analyze(AbstractTraceResult result, List<Session> sessionlist) {

		// PRESERVE CODE BLOCK for debugging, comment out for production

//		Level originalLevel = null;
//		if (settings.checkAttributeValue("env", "dev")) {
//			originalLevel = log.setLevel(Level.DEBUG);
//			log.info("Original logger level :" + originalLevel);
//		}

		loadPrefs();
		time0 = 0;

		tracePath = result.getTraceDirectory() + Util.FILE_SEPARATOR;
		log.elevatedInfo("VideoAnalysis for :" + tracePath);

		videoPath = tracePath + fileVideoSegments + Util.FILE_SEPARATOR;
		if (!filemanager.directoryExist(videoPath)) {
			filemanager.mkDir(videoPath);
		} else {
			filemanager.directoryDeleteInnerFiles(videoPath);
		}

		imagePath = tracePath + "Image" + Util.FILE_SEPARATOR;

		if (!filemanager.directoryExist(imagePath)) {
			imageExtractionRequired = true;
			filemanager.mkDir(imagePath);
		} else {
			// Not Required but needed if in case the method is called a second time after initialization
			imageExtractionRequired = false;
		}

		// clear out old objects
		aroManifest = null;
		videoUsage = new VideoUsage(result.getTraceDirectory());
		videoUsage.setVideoUsagePrefs(videoUsagePrefs);
		aroManifestMap = videoUsage.getAroManifestMap();

		// Go through trace-directory/download folder to load external manifests, if exist
		downloadPath = tracePath + "downloads" + Util.FILE_SEPARATOR;
		if (!filemanager.directoryExist(downloadPath)) {
			filemanager.mkDir(downloadPath);
		}

		String[] files = filemanager.list(downloadPath, null);
		// make sure files has manifest file extension before adding it to aroManifestMap
		double keyTimestamp = 0;
		AROManifest extManifest = null;

		for (String file : files) {
			String extension = extractExtensionFromName(file);
			File manifestFile = new File(downloadPath + file);
			byte[] content;
			if (extension != null) {
				switch (extension) {

				case ".mpd":
					content = fileToByteArray(manifestFile);
					aroManifest = new ManifestDash(null, content, videoPath);

					aroManifest.setDelay(videoUsagePrefs.getStartupDelay());

					aroManifestMap.put(keyTimestamp, aroManifest);
					keyTimestamp = keyTimestamp + 1000; // increment by 1ms
					break;

				case ".m3u8":
					content = fileToByteArray(manifestFile);
					if (extManifest == null) {
						try {
							extManifest = new ManifestHLS(null, content, videoPath);
							extManifest.setDelay(videoUsagePrefs.getStartupDelay());

							aroManifestMap.put(keyTimestamp, extManifest);
							keyTimestamp = keyTimestamp + 1000; // increment by 1ms
							if (aroManifest == null) {
								aroManifest = extManifest;
							}

						} catch (Exception e) {
							log.error("Failed to parse manifest data, Name:" + manifestFile.getName());
						}

					} else {
						try {
							((ManifestHLS) extManifest).parseManifestData(content);
						} catch (Exception e) {
							log.error("Failed to parse manifest data");
						}
					}
					break;
				default:// items found here may need to be handled OR ignored as
						// the above
					log.debug("failed with extention :" + extension);
					break;

				}
			}
		}

		reqMap = collectReqByTS(sessionlist);
		String imgExtension = null;
		String filename = null;

		for (HttpRequestResponseInfo req : reqMap.values()) {

			log.info(req.toString());

			if (req.getAssocReqResp() == null) {
				continue;
			}
			String oName = req.getObjNameWithoutParams();

			log.info("oName :" + req.getObjNameWithoutParams() + "\theader :" + req.getAllHeaders() + "\tresponse :" + req.getAssocReqResp().getAllHeaders());

			String fullName = extractFullNameFromRRInfo(req);
			String extn = extractExtensionFromName(fullName);
			if (extn == null) {
				if (oName.contains(".ism/")) {
					if (fullName.equals("manifest")) {
						aroManifest = extractManifestDash(req);
						continue;
					} else {
						if (fullName.contains("video")) {
							filename = truncateString(fullName, "_");
							extractVideo(req, aroManifest, filename);
						}
						continue;
					}
				}
				continue;
			}

			switch (extn) {

			// DASH - Amazon, Hulu, Netflix, Fullscreen
			case ".mpd": // Dash Manifest
				imgExtension = ".mp4";
				aroManifest = extractManifestDash(req);
				String message = aroManifest != null ? aroManifest.getVideoName() : "extractManifestDash FAILED";
				log.info("extract :" + message);
				break;

			case ".ism": // Dash/MPEG
				log.info("SSM");
			case ".mp4": // Dash/MPEG

				fullName = extractFullNameFromRRInfo(req);
				if (fullName.contains("_audio")) {
					continue;
				}

				vConfig = voConfigHelper.findConfig(req.getObjUri().toString());
				filename = extractNameFromRRInfo(req);
				extractVideo(req, aroManifest, filename);

				break;

			// DTV
			case ".m3u8": // HLS-DTV Manifest
				imgExtension = ".ts";
				if (!oName.contains("cc")) {
					aroManifest = extractManifestHLS(req);
				}
				break;

			case ".ts": // HLS video
				filename = "video" + imgExtension;
				extractVideo(req, aroManifest, filename);
				break;

			case ".vtt": // closed caption
				// filename = "videoTT.vtt";
				// extractVideo(req, aroManifest, filename);
				break;

			// Images
			case ".jpg":
			case ".gif":
			case ".tif":
			case ".png":
			case ".jpeg":

				if (imageExtractionRequired) {
					extractImage(req, aroManifest, fullName);
				}
				break;

			case ".css":
			case ".json":
			case ".html":
				continue;

			default:// items found here may need to be handled OR ignored as the above
				log.info("============ failed with extention :" + extn);
				break;
			}

			if (filename != null) {
				log.debug("filename :" + filename);
			}
		}
		
		updateDuration();
		
		log.info(videoUsage.toString());
		
		TreeMap<Double, HttpRequestResponseInfo> reqm = videoUsage.getRequestMap();
		log.info(String.format("VIDEO REQUESTS;"));
		Double key = 0.0;
		while (key != null) {
			log.info(String.format("reqm.get(%s) :%s", key, reqm.get(key)));
			key = reqm.higherKey(key);
		}
		
		// PRESERVE CODE BLOCK for debugging, comment out for production
//		if (originalLevel != null) {
//			log.setLevel(originalLevel);
//		}
		
		// Always delete the temp segment folder
		if (!checkDevMode()) {
			filemanager.directoryDeleteInnerFiles(videoPath);
			filemanager.deleteFile(videoPath);
		}

		return videoUsage;
	}

	/**
	 * <pre>
	 * Final pass to fix update values if not set already.
	 * Examine startTime for each segment compared with next segment to determine duration when not set.
	 * 
	 * Problems occur when there is a missing segment and on the last segment.
	 *  Missing segments cause an approximation by dividing the duration by the number of missing segments+1
	 *  The last segment simply repeats the previous duration, this should not skew results by much.
	 */
	private void updateDuration() {
		if (videoUsage != null) {
			for (AROManifest manifest : videoUsage.getAroManifestMap().values()) {
				NavigableMap<String, VideoEvent> eventMap = manifest.getSegmentEventList();
				if (!eventMap.isEmpty()) {
					int seg = 0;

					Entry<String, VideoEvent> lastEntry = eventMap.lastEntry();
					double lastSeg = lastEntry != null ? lastEntry.getValue().getSegment() : 0;

					String key = manifest.generateVideoEventKey(0, 0, "z");
					Entry<String, VideoEvent> val;
					Entry<String, VideoEvent> valn;

					double duration = 0;
					VideoEvent event;
					for (seg = 1; seg <= lastSeg; seg++) {
						String segNextKey = manifest.generateVideoEventKey(seg, 0, "z");
						val = eventMap.higherEntry(key);
						valn = eventMap.higherEntry(segNextKey);
						if (val == null || valn == null) {
							break;
						}

						event = val.getValue();
						VideoEvent eventNext = valn.getValue();

						duration = eventNext.getSegmentStartTime() - event.getSegmentStartTime();
						double deltaSegment = eventNext.getSegment() - event.getSegment();
						if (deltaSegment > 1) {
							duration /= deltaSegment;
						}

						for (VideoEvent subEvent : eventMap.subMap(key, segNextKey).values()) {
							if (subEvent.getDuration() <= 0) {
								subEvent.setDuration(duration);
							}
						}

						key = segNextKey;
					}
					val = eventMap.higherEntry(key);
					if (val != null) {
						event = val.getValue();
						if (event.getDuration() <= 0) {
							event.setDuration(duration);
						}
					}
				}
			}
		}
	}

	private boolean checkDevMode() {
		return settings.checkAttributeValue("env", "dev");
	}
	
	/**
	 * Create a TreeMap of all pertinent Requests keyed by timestamp
	 * 
	 * @param sessionlist
	 * @return Map of Requests
	 */
	private TreeMap<Double, HttpRequestResponseInfo> collectReqByTS(List<Session> sessionlist) {
		TreeMap<Double, HttpRequestResponseInfo> reqMap = new TreeMap<>();
		for (Session session : sessionlist) {
			List<HttpRequestResponseInfo> rri = session.getRequestResponseInfo();
			for (HttpRequestResponseInfo rrInfo : rri) {
				if (rrInfo.getDirection().equals(HttpDirection.REQUEST) 
						&& rrInfo.getRequestType() != null 
						&& rrInfo.getRequestType().equals(HttpRequestResponseInfo.HTTP_GET)
						&& rrInfo.getObjNameWithoutParams().contains(".")) {
					rrInfo.setSession(session);
					reqMap.put(rrInfo.getTimeStamp(), rrInfo);
				}
			}

			// Set a forward link for all packets in session to the next packet (within the session).
			// The last packet in session will not link anywhere of course!
			List<PacketInfo> packets = session.getPackets();
			for (int i = 0; i < packets.size() - 1; i++) {
				packets.get(i).getPacket().setNextPacketInSession(packets.get(i + 1).getPacket());
			}
		}
		return reqMap;
	}

	private String getDebugPath() {
		return videoPath;
	}
	
	
	private String getImagePath() {
		return imagePath;
	}

	/**
	 * Parse filename out of URI in HttpRequestResponseInfo
	 * 
	 * @param rrInfo
	 *            HttpRequestResponseInfo
	 * @return
	 */
	private String extractFullNameFromRRInfo(HttpRequestResponseInfo rrInfo) {
		String URI = rrInfo.getObjNameWithoutParams();
		int pos = URI.lastIndexOf("/");
		String fullName = URI.substring(pos + 1, URI.length());
		return fullName;
	}
	
	/**
	 * <pre>
	 * if and extention then Match on name and exten, then append.
	 * else return all after last '/'
	 * 
	 * @param rrInfo
	 * @return
	 */
	private String extractNameFromRRInfo(HttpRequestResponseInfo rrInfo) {
		String[] results = null;
		try {
			results = stringParse.parse(rrInfo.getObjNameWithoutParams(), "([a-zA-Z0-9\\-]*)[_\\.]");
			if (results == null || results.length == 0) {
				String fullName = extractFullNameFromRRInfo(rrInfo);
				int pos = fullName.indexOf('_');
				return pos == -1 ? fullName : fullName.substring(0, pos);
			}
		} catch (Exception e) {
			log.error("Exception :" + e.getMessage());
		}
		StringBuilder name = new StringBuilder("");
		if (results != null) {
			for (String part : results) {
				name.append(part);
			}
		}
		return name.toString();
	}
	
	
	/**
	 * Returns string from the target to the end of the string
	 * 
	 * @param src
	 * @param target
	 * @return
	 */
	private String truncateString(String src, String target) {
		int pos = src.indexOf(target);
		if (pos > -1) {
			return src.substring(pos);
		}
		return src;
	}

	/**
	 * Locate and return extension from filename
	 * 
	 * @param src
	 * @return String extension
	 */
	private String extractExtensionFromName(String src) {
		int pos = src.lastIndexOf('.');
		if (pos > 0) {
			return src.substring(pos);
		}
		return null;
	}

	/**
	 * Extract a video from traffic data
	 * 
	 * @param request
	 * @param session
	 * @param aroManifest
	 * @param fileName
	 */
	public void extractVideo(HttpRequestResponseInfo request, AROManifest aroManifest, String fileName) {
		HttpRequestResponseInfo response = request.getAssocReqResp();
		Session session = request.getSession();

		log.info("-------");
		log.info(request.getObjUri().toString());
		log.info(request.getAllHeaders());
		log.info(request.getAssocReqResp().getAllHeaders());

		String quality = "";
		ArrayList<ByteRange> rangeList = new ArrayList<>();
		double bitrate = 0;
		double segment = 0;
		double duration = 0;

		String fullName = extractFullNameFromRRInfo(request);
		byte[] content = null;
		String fullpath;

		String[] voValues = null;
		vConfig = voConfigHelper.findConfig(request.getObjUri().toString());
		VideoEventData ved = null;
		if (vConfig != null) {
			log.info("vConfig :" + vConfig);
			voValues = voConfigHelper.match(vConfig, request.getObjUri().toString(), request.getAllHeaders(), request.getAssocReqResp().getAllHeaders());

			ved = voEventDataHelper.create(vConfig, voValues);
			log.info(ved.toString());
		}

		if (ved == null) {
			log.info(" [[[[[[[[[[[[[[[[[[ no REGEX MATCH! ]]]]]]]]]]]]]]]]]]]");
			ved = voEventDataHelper.create(extractNameFromRRInfo(request), extractExtensionFromName(extractFullNameFromRRInfo(request)));
			ved.setSegment(-4);
		}

		log.debug("aroManifest :" + aroManifest);

		aroManifest = matchManifest(request, aroManifest, ved);
		quality = ved.getQuality() == null ? "unknown":ved.getQuality();
		rangeList.add(ved.getByteRange());
		segment = aroManifest.parseSegment(fullName, ved);

		if (ved.getId() != null && !ved.getId().equals(aroManifest.getVideoName())) {
			aroManifest = locateManifestForVideo(aroManifest, vConfig, ved, request);
		}

		double segmentStartTime = 0;
		if (aroManifest instanceof ManifestDash) {
			bitrate = ((ManifestDash) aroManifest).getBandwith(truncateString(fullName, "_"));
		} else if (aroManifest instanceof ManifestHLS) {
			duration = aroManifest.getDuration();
			try {
				if (quality.matches("[-+]?\\d*\\.?\\d+")) {
					Double temp = aroManifest.getBitrate(quality);
					if (temp == null) {
						temp = aroManifest.getBitrate("HLS" + quality);
					}
					bitrate = temp != null ? temp : 0D;
				} else {
					// TODO need to handle different format of Request for an AD perhaps
				}
			} catch (Exception e) {
				log.info("invalid quality :" + quality);
			}

		} else {
			// TODO some other way
		}

		log.debug("trunk " + fileName + ", getTimeString(response) :" + getTimeString(response));
		byte[] thumbnail = null;
		Integer[] segmentMetaData = new Integer[2];

		String segName = null;

		try {
			content = reqhelper.getContent(response, session);
			if (content.length == 0) {
				videoUsage.addFailedRequestMap(request);
				return;
			}
			segmentMetaData[0] = content.length;

			videoUsage.addRequest(request);

			if (aroManifest instanceof ManifestDash) {
				segmentMetaData = parsePayload(content);
			} else if (aroManifest instanceof ManifestHLS) { // FIXME - not valid dtv1-Live,

				if (time0 == 0 && segmentStartTime != 0) {
					time0 = segmentStartTime;
				}
				segmentStartTime -= time0;
			}

			if (segment < 0 && segmentMetaData[1] != null) {
				segment = segmentMetaData[1];

			}

			fullpath = constructDebugName(request, ved);

			log.debug(fileName + ", content.length :" + content.length);

			if (segment == 0 && aroManifest.isVideoType(VideoType.DASH)) {
				VideoData vData = new VideoData(aroManifest.getEventType(), quality, content);
				aroManifest.addVData(vData);
			} else {
				String seg = String.format("%08d", ((Double) segment).intValue());
				segName = getDebugPath() + seg + '_' + ved.getId() + '.' + ved.getExtension();
				thumbnail = extractThumbnail(aroManifest, content, ved);
			}
			filemanager.saveFile(new ByteArrayInputStream(content), fullpath);

		} catch (Exception e) {

			videoUsage.addFailedRequestMap(request);
			log.debug("Failed to extract " + getTimeString(response) + fileName + ", range: " + ved.getByteRange() + ", error: " + e.getMessage());
			return;
		}

		TreeMap<String, Double> metaData = null;
		if (thumbnail != null) {
			metaData = extractMetadata(segName);
			if (metaData != null) {
				Double val = metaData.get("bitrate");
				if (val != null) {
					bitrate = val;
				}
				val = metaData.get("Duration");
				if (val != null) {
					duration = val;
				}
				val = metaData.get("SegmentStart");
				if (val != null) {
					segmentStartTime = val;
				}
			}
		}

		if (segment > 0) {
			segmentStartTime = segmentMetaData[1] != null ? segmentMetaData[1].doubleValue() / 120000 : 0;
		}

		
			// a negative value indicates segment startTime
			// later will scan over segments & set times based on next segmentStartTime
			duration -= segmentStartTime;
		

		VideoEvent vEvent = new VideoEvent(thumbnail, aroManifest.getEventType(), segment, quality, rangeList, bitrate, duration, segmentStartTime, segmentMetaData[0], response);
		aroManifest.addVideoEvent(segment, response.getTimeStamp(), vEvent);

	}

	private AROManifest locateManifestForVideo(AROManifest aroManifest, VideoAnalysisConfig vConfig, VideoEventData ved, HttpRequestResponseInfo request) {
		log.debug(String.format("diff :%s != %s\n", ved.getId(), aroManifest.getVideoName()));
		for (AROManifest manifest : aroManifestMap.values()) {
			if (ved.getId().equals(aroManifest.getVideoName())) {
				return manifest;
			}
		}
		AROManifest manifest = null;
		if (vConfig == null) {
			manifest = new AROManifest(VideoType.UNKNOWN, request, videoPath);
		} else if (vConfig.getVideoType().equals(VideoType.DASH)) {
			manifest = new ManifestDash(null, request, videoPath);
		} else {
			manifest = new ManifestHLS(request, videoPath);
		}
		manifest.setVideoName(ved.getId());
		aroManifestMap.put(request.getTimeStamp(), manifest);
		return manifest;
	}

	private String constructDebugName(HttpRequestResponseInfo request, VideoEventData ved) {

		String fullpath;
		StringBuffer fname = new StringBuffer(getDebugPath());
		fname.append(ved.getId());
		fname.append('_');			fname.append(String.format("%08d", ved.getSegment()));
		if (ved.getByteRange() != null) {
		fname.append("_R_");		fname.append(ved.getByteRange());}
		fname.append("_dl_");		fname.append(getTimeString(request.getAssocReqResp()));
		fname.append("_S_");		fname.append(String.format("%08.0f", request.getSession().getSessionStartTime()*1000));
		fname.append("_Q_");		fname.append(ved.getQuality());
		fname.append('.');			fname.append(ved.getExtension());
		fullpath = fname.toString();
		return fullpath;
	}

	/**
	 * Creates a TreeMap with keys:
	 *    bitrate
	 *    Duration
	 *    SegmentStart
	 * 
	 * @param srcpath
	 * @return
	 */
	private TreeMap<String, Double> extractMetadata(String srcpath) {
		TreeMap<String, Double> results = new TreeMap<>();
		String cmd = Util.getFFMPEG() + " -i " + srcpath;
		String lines = extrunner.executeCmd(cmd);
		if (lines.indexOf("No such file") == -1) {
			double bitrate = getBitrate("bitrate: ", lines);
			results.put("bitrate", bitrate);
			
			Double duration = 0D;
			
			String[] time = StringParse.findLabeledDataFromString("Duration: ", ",", lines).split(":"); // 00:00:05.80
			double start = StringParse.findLabeledDoubleFromString(", start:", ",", lines);				// 2.711042
			
			if (time.length == 3) {
				try {
					duration = Integer.parseInt(time[0]) * 3600 + Integer.parseInt(time[1]) * 60 + Double.parseDouble(time[2]);
				} catch (NumberFormatException e) {
					log.error("failed to parse duration from :" + StringParse.findLabeledDataFromString("Duration: ", ",", lines));
					duration = 0D;
				}
			} else if (time.length > 0) {
				try {
					duration = Double.parseDouble(time[0]);
				} catch (NumberFormatException e) {
					log.error("failed to parse duration from :" + time[0]);
					duration = 0D;
				}
			}
			results.put("Duration", duration);
			results.put("SegmentStart", start);
		}
		return results;
	}

	/**
	 * verify video belongs with a manifest
	 * 
	 * @param request
	 * @param aroManifest
	 * @return correct AroManifest
	 */
	private AROManifest matchManifest(HttpRequestResponseInfo request, AROManifest aroManifest, VideoEventData ved) {
		HttpRequestResponseInfo response = request.getAssocReqResp();

		String objName = ved.getId();// extractFullNameFromRRInfo(request);
		String videoName = "";
		AROManifest manifest;
		if (aroManifest == null) {
			aroManifest = videoUsage.findVideoInManifest(objName);
			if (aroManifest == null) {

				if (objName.contains("_video_")) {
					aroManifest = new ManifestDash(null, request, videoPath);
				} else {
					aroManifest = new AROManifest(VideoType.UNKNOWN, response, videoPath);
					ved.setSegment(0);
					ved.setQuality("unknown");
				}
				aroManifest.setVideoName(ved.getId());
				videoUsage.add(request.getTimeStamp(), aroManifest);
			}
		}

		videoName = aroManifest.getVideoName();

		if (videoName != null && !videoName.isEmpty() && objName != null && !objName.contains(videoName)) {
			manifest = videoUsage.findVideoInManifest(objName);
			aroManifest = manifest != null ? manifest : aroManifest;
			videoName = aroManifest.getVideoName();
		}
		return aroManifest;
	}

	/**
	 * Parse mp4 chunk/segment that contains one moof and one mdat.
	 * 
	 * @param content
	 * @return double[]  mdat payload length, time sequence
	 */
	private Integer[] parsePayload(byte[] content) {
		byte[] buf = new byte[4];
		int mdatSize = 0;
		ByteBuffer bbc = ByteBuffer.wrap(content);

		// get moof size
		double moofSize = bbc.getInt();
		bbc.get(buf);
		String moofName = new String(buf);
		int timeSequence = 0;
		if (moofName.equals("moof")) {

			// skip past mfhd
			double mfhdSize = bbc.getInt();
			bbc.get(buf);
			String mfhdName = new String(buf);
			if (mfhdName.equals("mfhd")) {
				bbc.position((int) mfhdSize + bbc.position() - 8);

				// parse into traf
				//double trafSize = 
				bbc.getInt(); //skip over
				bbc.get(buf);
				String trafName = new String(buf);
				if (trafName.equals("traf")) {

					// skip tfhd
					double tfhdSize = bbc.getInt();
					bbc.get(buf);
					String tfhdName = new String(buf);
					if (tfhdName.equals("tfhd")) {
						// skip past this atom
						bbc.position((int) tfhdSize + bbc.position() - 8);
					}

					// parse tfdt
					// double tfdtSize = 
					bbc.getInt(); //skip over
					bbc.get(buf);
					String tfdtName = new String(buf);
					if (tfdtName.equals("tfdt")) {
						bbc.getInt(); // skip over always 16k
						bbc.getInt(); // skip over always 0
						timeSequence = bbc.getInt();
					}
				}
			}
		} else {
			return new Integer[] { 0, 0 };
		}

		// parse mdat
		bbc.position((int) moofSize);
		mdatSize = bbc.getInt();
		bbc.get(buf, 0, 4);
		String mdatName = new String(buf);
		if (mdatName.equals("mdat")) {
			mdatSize -= 8;
		} else {
			mdatSize = 0;
		}
		
		return new Integer[] { mdatSize, timeSequence };
	}


	/**
	 * Extract a Thumbnail image from the first frame of a video
	 * 
	 * @param aroManifest
	 * @param content
	 * 
	 * @param srcpath
	 * @param segmentName
	 * @param quality
	 * @param videoData
	 * @return
	 */
	private byte[] extractThumbnail(AROManifest aroManifest, byte[] content, VideoEventData ved) {
		
		String //segName = getDebugPath() + String.format("%08d", ved.getIntSegment()) + '_' + ved.getId() + '.' + ved.getExtension();
		segName = (new StringBuilder(getDebugPath()))
				.append(String.format("%08d", ved.getSegment()))
				.append('_')
				.append(ved.getId())
				.append('.')
				.append(ved.getExtension())
				.toString();
		
		byte[] data = null;
		filemanager.deleteFile(ved.getId());

		VideoData vData = aroManifest.getVData(ved.getQuality());
		if (vData == null) {
			return null;
		}

		// join mbox0 with segment
		byte[] mbox0 = vData.getContent();
		byte[] movie = new byte[mbox0.length + content.length];
		System.arraycopy(mbox0, 0, movie, 0, mbox0.length);
		System.arraycopy(content, 0, movie, mbox0.length, content.length);

		try {
			filemanager.saveFile(new ByteArrayInputStream(movie), segName);
		} catch (IOException e1) {
			log.error("IOException:"+e1.getMessage());
		}

		data = extractVideoFrameShell(segName);

		return data;
	}

	private byte[] extractVideoFrameShell(String segmentName) {
		byte[] data = null;
		String thumbnail = getDebugPath() + "thumbnail.png";
		filemanager.deleteFile(thumbnail);

		String cmd = Util.getFFMPEG() + " -y -i " + segmentName + " -ss 00:00:00   -vframes 1 " + thumbnail;
		String ff_lines = extrunner.executeCmd(cmd);
		log.debug("ff_lines :" + ff_lines);

		Path path = Paths.get(thumbnail);
		try {
			data = Files.readAllBytes(path);
		} catch (IOException e) {
			log.debug("getThumnail IOException:" + e.getMessage());
		}
		return data;
	}

	/**
	 * <pre>
	 * get a bitrate where the raw data will have a value such as: 150 kb/s 150 mb/s
	 * 
	 * @param key
	 * @param ff_lines
	 * @return
	 */
	private double getBitrate(String key, String ff_lines) {
		double bitrate = 0;
		String valbr = getValue(key, "\n", ff_lines);
		if (valbr != null) {
			String[] temp = valbr.split(" ");
			try {
				bitrate = Double.valueOf(temp[0]);
			} catch (NumberFormatException e) {
				log.debug("Bit rate not available for key:" + key);
				return 0;
			}
			if (temp[1].equals("kb/s")) {
				bitrate *= 1024;
			} else if (temp[1].equals("mb/s")) {
				bitrate *= 1048576;
			}
		}
		return bitrate;
	}

	/**
	 * Get the value following the key up to the delimiter. return null if not found
	 * 
	 * @param key
	 * @param delimeter
	 * @param ff_lines
	 * @return value or null if no key found
	 */
	private String getValue(String key, String delimeter, String ff_lines) {
		String val = null;
		int pos1 = ff_lines.indexOf(key);
		if (pos1 > -1) {
			pos1 += key.length();
			int pos2 = ff_lines.substring(pos1).indexOf(delimeter);
			if (pos2 == -1 || pos2 == 0) {
				val = ff_lines.substring(pos1);
			} else {
				val = ff_lines.substring(pos1, pos1 + pos2);
			}
		}
		return val;
	}

	/**
	 * Obtain timestamp from request formated into a string. Primarily for debugging purposes.
	 * 
	 * @param response
	 * @return
	 */
	private String getTimeString(HttpRequestResponseInfo response) {
		StringBuffer strTime = new StringBuffer();
		try {
			if (absTimeFlag) {
				Packet packet = response.getFirstDataPacket().getPacket(); // request
				strTime.append(String.format("%d.%06d"
						, packet.getSeconds()
						, packet.getMicroSeconds()
						));
			} else {
				strTime.append(String.format("%09.0f", (float) response.getTimeStamp()*1000));
			}
		} catch (Exception e) {
			log.error("Failed to get time from request: " + e.getMessage());
			strTime.append("Failed to get time from response->request: " + response);
		}
		return strTime.toString();
	}

	/**
	 * <pre>
	 * Extract a DTV/HLS manifest from traffic data
	 * 
	 * Types:
	 *  * movie
	 *  * livetv
	 *  
	 * @param request
	 * @param session
	 *            that the response belongs to.
	 * @return AROManifest
	 */
	public AROManifest extractManifestHLS(HttpRequestResponseInfo request) {
		HttpRequestResponseInfo response = request.getAssocReqResp();
		Session session = request.getSession();

		String videoName = null;
		
		byte[] content = null;
		try {
			content = reqhelper.getContent(response, session);
			if (content == null || content.length == 0) {
				videoUsage.addFailedRequestMap(request);
				return aroManifest;
			}

			videoUsage.addRequest(request);
			log.info("Manifest content.length :" + content.length);
			StringBuffer fname = new StringBuffer(getDebugPath());
			fname.append(getTimeString(response));
			
			videoName = regexNameFromRequestHLS(request);

			fname.append('_');
			fname.append(videoName);
			fname.append("_ManifestHLS.m3u8");
			filemanager.saveFile(new ByteArrayInputStream(content), fname.toString());

		} catch (Exception e) {
			videoUsage.addFailedRequestMap(request);
			log.error("Failed to get content from DTV Manifest; response: " + e.getMessage());
		}

		String getName = request.getObjNameWithoutParams();
		

		if (aroManifest != null && !aroManifest.checkContent(content)) {
			return aroManifest;
		}
		
		log.info(getName);
		if (aroManifest == null 
//				|| (getName.contains("/Live/") && getName.contains("channel"))
				|| (getName.contains("livetv") && getName.contains("latest"))
				|| (!getName.contains("livetv") && !getName.contains("_"))
				) {
			ManifestHLS manifest = null;
			try {
				manifest = new ManifestHLS(response, videoName, content, videoPath);
				manifest.setDelay(videoUsagePrefs.getStartupDelay());
				log.info("aroManifest :" + aroManifest);

				aroManifestMap.put(request.getTimeStamp(), manifest);
				return manifest;

			} catch (Exception e) {
				log.error("Failed to parse manifest data, absTimeStamp:" + request.getAbsTimeStamp().getTime() + ", Name:" + getName);
			}

		} else {
			try {
				((ManifestHLS) aroManifest).parseManifestData(content);
			} catch (Exception e) {
				log.error("Failed to parse manifest data into ManifestHLS:" + e.getMessage());
			}
		}

		return aroManifest;
	}

	private String regexNameFromRequestHLS(HttpRequestResponseInfo request) {
		
		String videoName = null;
		
		// TODO future create regex files for manifest matching like with video segments
		String regex[] = { 
				  "livetv\\/\\d+\\/([a-zA-Z0-9]*)\\/latest\\.m3u8"                      //  /livetv/30/8249/latest.m3u8
				, "livetv\\/\\d+\\/([a-zA-Z0-9]*)\\/\\d+\\/playlist\\.m3u8"             //  /livetv/30/8249/03/playlist.m3u8
				, "\\/aav\\/.+\\/([A|B]\\d+U)\\d\\.m3u8"                                //  /aav/30/B001573958U3/B001573958U3.m3u8
				, "\\/aav\\/.+\\/HLS\\d\\/([A|B]\\d+U)\\d\\_\\d.m3u8"                   //  /aav/30/B001844891U3/HLS2/B001844891U0_2.m3u8
				, "\\/aav\\/.+\\/WebVTT\\d\\/([A|B]\\d+U)\\d\\_\\d.m3u8"				//  /aav/30/B001844891U3/WebVTT1/B001844891U0_7.m3u8
				, "\\/movie\\/.+\\/([A|B]\\d+U)\\d\\.m3u8"                              //  /c3/30/movie/2016_12/B002021484/B002021484U3/B002021484U3.m3u8
				, "\\/channel\\((.+)\\)\\/\\d+.m3u8"	                  				//  /Content/HLS_hls.pr/Live/channel(FNCHD.gmott.1080.mobile)/05.m3u8
				, "\\/channel\\((.+)\\)\\/index.m3u8"	                  				//  /Content/HLS_hls.pr/Live/channel(FNCHD.gmott.1080.mobile)/index.m3u8
				, "\\/([a-zA-Z0-9]*).m3u8"                             					//  /B002021484U3.m3u8
		};
		
		String[] results = null;
		
		for (int i = 0; i < regex.length; i++) {
			results = stringParse.parse(request.getObjNameWithoutParams(), regex[i]);
			if (results != null) {
				videoName = results[0].replaceAll("\\.", "_");
				return videoName;
			}
		}

		if (results == null) {
			videoName = "unknown";
		}
		return videoName;
	}

	/**
	 * Extract a DASH manifest from traffic data
	 * 
	 * @param request
	 * @param session
	 *            session that the response belongs to.
	 * @return AROManifest
	 * @throws java.lang.Exception
	 */
	public AROManifest extractManifestDash(HttpRequestResponseInfo request) {
		HttpRequestResponseInfo response = request.getAssocReqResp();
		Session session = request.getSession();
		byte[] content = null;

		try {
			content = reqhelper.getContent(response, session);
			if (content.length == 0) {
				videoUsage.addFailedRequestMap(request);
				return aroManifest;
			}

			videoUsage.addRequest(request);
			log.info("Manifest content.length :" + content.length);
		
			// debug - save to debug folder
			saveManifestFile(request, content);

		} catch (Exception e) {
			videoUsage.addFailedRequestMap(request);
			log.error("Failed to parse manifest data, absTimeStamp:" + request.getAbsTimeStamp().getTime() + ", Name:" + request.getObjNameWithoutParams());
		}

		AROManifest manifest = null;
		try {
			manifest = new ManifestDash(response, content, videoPath);
			manifest.setDelay(videoUsagePrefs.getStartupDelay());
			log.info("aroManifest :" + aroManifest);
			
			for (AROManifest checkManifest : videoUsage.getAroManifestMap().values()) {
				if (checkManifest.getVideoName().equals(manifest.getVideoName())) {
					// don't create duplicates
					return checkManifest;
				}
			}

		} catch (Exception e) {
			log.error("Failed to parse manifest data:"+e.getMessage());
		}

		aroManifestMap.put(request.getTimeStamp(), manifest);

		return manifest;
	}
	
	/**
	 * Saves byte[] to a file
	 * 
	 * @param request
	 * @param content
	 * @throws IOException
	 */
	private void saveManifestFile(HttpRequestResponseInfo request, byte[] content) throws IOException {
		StringBuffer fname = new StringBuffer(getDebugPath());
		String temp = extractNameFromRRInfo(request);
		fname.append(temp.equals("manifest") ? "_SSM_manifest" : temp);
		fname.append("__");
		fname.append(getTimeString(request.getAssocReqResp()));
		if (request.getObjNameWithoutParams().endsWith("manifest")) {
			fname.append("_SSMedia.xml");
		} else {
			fname.append("_ManifestDash.mpd");
		}
		filemanager.saveFile(new ByteArrayInputStream(content), fname.toString());
	}
	
	private byte[] fileToByteArray(File file) {
		byte[] content = new byte[(int) file.length()];
		FileInputStream fileInputStream;
		try {
			fileInputStream = new FileInputStream(file);
			fileInputStream.read(content);
		} catch (IOException e) {
			log.error("File to byte array conversion exception" + e.getMessage());
		}
		return content;
	}
	
	public void extractImage(HttpRequestResponseInfo request, AROManifest aroManifest, String imageFileName) {

		HttpRequestResponseInfo response = request.getAssocReqResp();
		Session session = request.getSession();

		if (response != null) {
			byte[] content = null;
			String fullpath;

			try {
				content = reqhelper.getContent(response, session);
				fullpath = getImagePath() + imageFileName;
				filemanager.saveFile(new ByteArrayInputStream(content), fullpath);
			} catch (Exception e) {
				videoUsage.addFailedRequestMap(request);
				log.info("Failed to extract " + getTimeString(response) + imageFileName + " response: " + e.getMessage());
				return;
			}
		}
	}

	@Override
	public VideoUsage getVideoUsage() {
		return videoUsage;
	}

	@Override
	public TreeMap<Double, HttpRequestResponseInfo> getReqMap() {
		return reqMap;
	}

}// end class
