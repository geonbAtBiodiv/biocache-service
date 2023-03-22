/**************************************************************************
 * Copyright (C) 2013 Atlas of Living Australia
 * All Rights Reserved.
 * <p/>
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 * <p/>
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.web;

import au.org.ala.biocache.dao.PersistentQueueDAO;
import au.org.ala.biocache.dao.SearchDAO;
import au.org.ala.biocache.dto.DownloadDetailsDTO;
import au.org.ala.biocache.dto.DownloadRequestDTO;
import au.org.ala.biocache.dto.DownloadRequestParams;
import au.org.ala.biocache.dto.DownloadStatusDTO;
import au.org.ala.biocache.service.AuthService;
import au.org.ala.biocache.service.DownloadService;
import au.org.ala.ws.security.profile.AlaUserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.ApiParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.common.SolrDocumentList;
import org.springdoc.api.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A Controller for downloading records based on queries.  This controller
 * will provide methods for offline asynchronous downloads of large result sets.
 * <ul>
 * <li> persistent queue to contain the offline downloads. - written to filesystem before emailing to supplied user </li>
 * <li> administering the queue - changing order, removing items from queue </li>
 * </ul>
 * @author Natasha Carter (natasha.carter@csiro.au)
 */
@Controller
@JsonInclude(JsonInclude.Include.NON_NULL)
@Slf4j
public class DownloadController extends AbstractSecureController {

    final private static Logger logger = Logger.getLogger(ScatterplotController.class);

    /** Fulltext search DAO */
    @Inject
    protected SearchDAO searchDAO;

    @Inject
    protected PersistentQueueDAO persistentQueueDAO;

    @Inject
    protected AuthService authService;

    @Inject
    protected DownloadService downloadService;

    /**
     * Retrieves all the downloads that are on the queue
     * @return
     */
    @Deprecated
    @SecurityRequirement(name="JWT")
    @Secured({"ROLE_ADMIN"})
    @Operation(summary = "Retrieves all the downloads that are on the queue", tags = "Monitoring")
    @RequestMapping(value = {"occurrences/offline/download/stats"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody List getCurrentDownloads() throws Exception {
        return allOccurrenceDownloadStatus();
    }

    /**
     * Add a download to the offline queue
     * @param requestParams
     * @param ip
     * @param response
     * @param request
     * @return
     * @throws Exception
     */
    @Operation(summary = "Asynchronous occurrence download", tags = "Download")
    @Tag(name ="Download", description = "Services for downloading occurrences and specimen data")
    @RequestMapping(value = { "occurrences/offline/download"}, method = {RequestMethod.GET, RequestMethod.POST})
    public @ResponseBody DownloadStatusDTO occurrenceDownload(
            @Valid @ParameterObject DownloadRequestParams requestParams,
            @Parameter(description = "Original IP making the request") @RequestParam(value = "ip", required = false) String ip,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        DownloadRequestDTO downloadRequestDTO = DownloadRequestDTO.create(requestParams, request);
        Optional<AlaUserProfile> downloadUser = authService.getDownloadUser(downloadRequestDTO, request);

        if (!downloadUser.isPresent()){
            response.sendError(400, "No valid email");
            return null;
        }

        return download(
                downloadRequestDTO,
                downloadUser.get(),
                ip,
                request.getHeader("user-agent"),
                request,
                response,
                DownloadDetailsDTO.DownloadType.RECORDS_INDEX);
    }

    private DownloadStatusDTO download(DownloadRequestDTO requestParams,
                                         AlaUserProfile alaUser,
                                         String ip,
                                         String userAgent,
                                         HttpServletRequest request,
                                         HttpServletResponse response,
                                         DownloadDetailsDTO.DownloadType downloadType) throws Exception {

        // check the email is supplied and a matching user account exists with the required privileges
        if (alaUser == null || StringUtils.isEmpty(alaUser.getEmail())) {
            response.sendError(HttpServletResponse.SC_PRECONDITION_FAILED, "Unable to perform an offline download without an email address");
            return null;
        }

        ip = ip == null ? request.getRemoteAddr() : ip;

        //create a new task
        DownloadDetailsDTO dd = new DownloadDetailsDTO(requestParams, alaUser, ip, userAgent, downloadType);

        //get query (max) count for queue priority
        requestParams.setPageSize(0);
        requestParams.setFacet(false);
        SolrDocumentList result = searchDAO.findByFulltext(requestParams);
        dd.setTotalRecords(result.getNumFound());

        DownloadStatusDTO status = new DownloadStatusDTO();
        DownloadDetailsDTO d = persistentQueueDAO.isInQueue(dd);

        status = getQueueStatus(d);

        if (d != null) {
            status.setMessage("Already in queue.");
            status.setStatus(DownloadStatusDTO.DownloadStatus.IN_QUEUE);
        } else if (dd.getTotalRecords() > downloadService.dowloadOfflineMaxSize) {
            //identify this download as too large
            File file = new File(downloadService.biocacheDownloadDir + File.separator + UUID.nameUUIDFromBytes(dd.getRequestParams().getEmail().getBytes(StandardCharsets.UTF_8)) + File.separator + dd.getStartTime() + File.separator + "tooLarge");
            FileUtils.forceMkdir(file.getParentFile());
            FileUtils.writeStringToFile(file, "", "UTF-8");
            status.setDownloadUrl(downloadService.biocacheDownloadUrl);
            status.setStatus(DownloadStatusDTO.DownloadStatus.SKIPPED);
            status.setMessage(downloadService.downloadOfflineMsg);
            status.setError("Requested to many records (" + dd.getTotalRecords() + "). The maximum is (" + downloadService.dowloadOfflineMaxSize + ")");
        } else {
            downloadService.add(dd);
            status = getQueueStatus(dd);
        }

        writeStatusFile(dd.getUniqueId(), status);

        return status;
    }

    @SecurityRequirement(name="JWT")
    @Secured({"ROLE_ADMIN"})
    @Operation(summary = "List all occurrence downloads", tags = "Monitoring")
    @RequestMapping(value = {"occurrences/offline/status/admin"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody List<DownloadStatusDTO> allOccurrenceDownloadStatus() throws Exception {

        List<DownloadStatusDTO> allStatus = new ArrayList<>();

        //is it in the queue?
        List<DownloadDetailsDTO> downloads = persistentQueueDAO.getAllDownloads();
        for (DownloadDetailsDTO dd : downloads) {
            allStatus.add(getQueueStatus(dd));
        }

        return allStatus;
    }

    @Secured({"ROLE_USER"})
    @Operation(summary = "List all occurrence downloads", tags = "Monitoring")
    @RequestMapping(value = {"occurrences/offline/status"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody List<DownloadStatusDTO> allOccurrenceDownloadStatusForUser(HttpServletRequest request) throws Exception {

        AlaUserProfile alaUserProfile = authService.getRecordViewUser(request).get();
        List<DownloadStatusDTO> allStatus = new ArrayList<>();

        //is it in the queue?
        List<DownloadDetailsDTO> downloads = persistentQueueDAO.getAllDownloads();
        for (DownloadDetailsDTO dd : downloads) {
            if (dd.getAlaUser().getUserId() == alaUserProfile.getUserId()) {
                allStatus.add(getQueueStatus(dd));
            }
        }

        return allStatus;
    }

    private void writeStatusFile(String id, DownloadStatusDTO status) throws IOException {
        File statusDir = new File(downloadService.biocacheDownloadDir + "/" + id.replaceAll("-([0-9]*)$", "/$1"));
        statusDir.mkdirs();
        String json = net.sf.json.JSONObject.fromObject(status).toString();
        FileUtils.writeStringToFile(new File(statusDir.getPath() + "/status.json"), json, "UTF-8");
    }

    @Operation(summary = "Get the status of download", tags = "Download")
    @RequestMapping(value = { "occurrences/offline/status/{id}"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiParam(value = "id", required = true)
    public @ResponseBody DownloadStatusDTO occurrenceDownloadStatus(@PathVariable("id") String id) throws Exception {
        DownloadStatusDTO status = new DownloadStatusDTO();

        //is it in the queue?
        List<DownloadDetailsDTO> downloads = persistentQueueDAO.getAllDownloads();
        for (DownloadDetailsDTO dd : downloads) {
            if (id.equals(dd.getUniqueId())) {
                return getQueueStatus(dd);
            }
        }

        String cleanId = id.replaceAll("[^a-z\\-0-9]", "");
        cleanId = cleanId.replaceAll("-([0-9]*)$", "/$1");

        return getOtherStatus(cleanId);
    }

    private DownloadStatusDTO getQueueStatus(DownloadDetailsDTO dd) {
        DownloadStatusDTO status = new DownloadStatusDTO();

        if (dd != null) {
            String id = dd.getUniqueId();
            if (dd.getRecordsDownloaded().get() == 0) {
                status.setStatus(DownloadStatusDTO.DownloadStatus.IN_QUEUE);
                status.setQueueSize(downloadService.getDownloadsForUserId(dd.getAlaUser().getUserId()).size() );
            } else {
                status.setStatus(DownloadStatusDTO.DownloadStatus.RUNNING);
                status.setRecords(dd.getRecordsDownloaded().longValue());
            }
            status.setTotalRecords(dd.getTotalRecords());
            status.setStatusUrl(downloadService.webservicesRoot + "/occurrences/offline/status/" + id);
            if (authService.getMapOfEmailToId() != null) {
                status.setUserId(authService.getMapOfEmailToId().get(dd.getRequestParams().getEmail()));
            }
            status.setSearchUrl(downloadService.generateSearchUrl(dd.getRequestParams()));
            status.setCancelUrl(downloadService.webservicesRoot + "/occurrences/offline/cancel/" + dd.getUniqueId());
        }

        return status;
    }

    private DownloadStatusDTO getOtherStatus(String id) {
        DownloadStatusDTO status = new DownloadStatusDTO();

        // look for output files
        if (status.getStatus() == null) {
            File dir = new File(downloadService.biocacheDownloadDir + File.separator + id.replaceAll("-([0-9]*)$", "/$1"));
            if (dir.isDirectory() && dir.exists()) {
                for (File file : dir.listFiles()) {

                    // output zip exists
                    if (file.isFile() && file.getPath().endsWith(".zip") && file.length() > 0) {
                        status.setStatus(DownloadStatusDTO.DownloadStatus.FINISHED);
                        try {
                            status.setDownloadUrl(downloadService.biocacheDownloadUrl + File.separator + URLEncoder.encode(file.getPath().replace(downloadService.biocacheDownloadDir + "/", ""), "UTF-8").replace("%2F", "/").replace("+", "%20"));
                        } catch (UnsupportedEncodingException e) {
                            logger.error("Failed to URLEncode for id:" + id + ", " + e.getMessage());
                        }
                    }

                    // notification for large download
                    if (file.isFile() && "tooLarge".equals(file.getName())) {
                        status.setStatus(DownloadStatusDTO.DownloadStatus.SKIPPED);
                        status.setMessage(downloadService.downloadOfflineMsg);
                        status.setDownloadUrl(downloadService.dowloadOfflineMaxUrl);
                        status.setError("requested to many records. The upper limit is (" + downloadService.dowloadOfflineMaxSize + ")");
                    }
                }

                // output directory exists and there is no output file
                if (status.getStatus() == null) {
                    status.setStatus(DownloadStatusDTO.DownloadStatus.FAILED);
                }
            }
        }

        File file = new File(downloadService.biocacheDownloadDir + File.separator + id + "/status.json");
        if (status.getStatus() == null) {
            //check downloads directory for a status file
            if (file.exists()) {
                ObjectMapper om = new ObjectMapper();
                try {
                    status = om.readValue(file, DownloadStatusDTO.class);
                } catch (IOException e) {
                    logger.error("failed to read file: " + file.getPath() + ", " + e.getMessage());
                }

                // the status.json is only used when a download request is 'lost'. Use an appropriate status.
                status.setStatus(DownloadStatusDTO.DownloadStatus.UNAVAILABLE);
                status.setMessage("This download is unavailable.");
            }
        }

        if (status.getStatus() == null) {
            status.setStatus(DownloadStatusDTO.DownloadStatus.INVALID_ID);
        }

        // write final status to a file
        if (status.getStatus() != DownloadStatusDTO.DownloadStatus.INVALID_ID
                && status.getStatus() != DownloadStatusDTO.DownloadStatus.IN_QUEUE
                && status.getStatus() != DownloadStatusDTO.DownloadStatus.UNAVAILABLE) {
            try {
                writeStatusFile(id, status);
            } catch (IOException e) {
                logger.error("failed to write status file for id=" + id + ", " + e.getMessage());
            }
        }

        return status;
    }

    /**
     * Cancel queued download. Does not cancel a download in progress.
     *
     * @param id
     * @return
     * @throws Exception
     */
    @Secured({"ROLE_USER"})
    @Operation(summary = "Cancel an offline download", tags = "Monitoring")
    @RequestMapping(value = {"occurrences/offline/cancel/{id}"}, method = RequestMethod.GET)
    @ApiParam(value = "id", required = true)
    public @ResponseBody DownloadStatusDTO occurrenceDownloadCancel(
            @PathVariable("id") String id,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        DownloadStatusDTO status = new DownloadStatusDTO();

        //is it in the queue?
        List<DownloadDetailsDTO> downloads = persistentQueueDAO.getAllDownloads();
        for (DownloadDetailsDTO dd : downloads) {
            if (id.equals(dd.getUniqueId())) {
                // 2) Check for JWT / OAuth
                Principal userPrincipal = request.getUserPrincipal();
                if (userPrincipal != null && userPrincipal instanceof AlaUserProfile){
                    AlaUserProfile user = (AlaUserProfile) userPrincipal;
                    if (dd.getAlaUser().getUserId() == user.getUserId() || user.getRoles().contains("ROLE_ADMIN")) {
                        downloadService.cancel(dd);

                        status.setStatus(DownloadStatusDTO.DownloadStatus.CANCELLED);

                        return status;
                    }
                }
            }
        }

        return null;
    }
}
