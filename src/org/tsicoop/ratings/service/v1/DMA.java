package org.tsicoop.ratings.service.v1;

import org.json.simple.parser.JSONParser;
import org.tsicoop.ratings.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.Iterator;

/**
 * DMAService class handles all operations related to the Digital Maturity Assessment (DMA).
 * This includes fetching the questionnaire, saving progress, finalizing assessment, and validating blockchain anchors.
 */
public class DMA implements Action {

    // --- Configuration Constants ---
    private static final String EXPRESS_ANCHOR_API_URL = "http://express-middleware-service/api/v1/blockchain/anchor";
    private static final String EXPRESS_VERIFY_API_URL = "http://express-middleware-service/api/v1/verification/anchor";
    private static final String INTERNAL_SERVICE_TOKEN = "INTERNAL_JWT_SECRET_FOR_EXPRESS_COMMUNICATION"; // Placeholder
    private static final String API_URL = "/api/dma";

    /**
     * Handles all DMA operations via a single POST endpoint.
     */
    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        JSONObject input = null;
        JSONObject output = null;

        Long auditorId = InputProcessor.getUserId(req);

        try {
            input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            String txId = (String) input.get("txId");
            String tsiHash = (String) input.get("tsiHash");

            if (func == null || func.isEmpty()) {
                func = req.getParameter("_func");
            }

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing required '_func'.", req.getRequestURI());
                return;
            }

            // Extract Assessment ID (required for Save and Finalize)
            Long assessmentId = null;
            Object assessmentIdObj = input.get("assessmentId");
            if (assessmentIdObj != null && !assessmentIdObj.equals("New")) {
                try {
                    assessmentId = Long.parseLong(assessmentIdObj.toString());
                } catch (NumberFormatException e) {
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Invalid 'assessmentId' format.", req.getRequestURI());
                    return;
                }
            }

            switch (func.toLowerCase()) {
                case "get_dma_questionnaire":
                    output = getQuestionnaire();
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "get_cma_questionnaire":
    output = getCmaQuestionnaire();
    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
    break;
     
                case "get_cma_status":
                    output = getCmaStatus();
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "save_assessment":
                    // Auditor saves progress, score, and qualitative input.
                    output = saveAssessment(assessmentId, auditorId, input);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output); // Use SC_OK for save/update
                    break;

                case "save_cma_assessment":
                    output = saveCmaAssessment(assessmentId, auditorId, input);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "get_cma_assessment_details":
                    output = getCmaAssessmentDetails(assessmentId, auditorId);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "publish_cma_assessment":
                    output = publishCmaAssessment(assessmentId, auditorId);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "finalize_assessment":
                    // Auditor confirms final submission

                    output =  updateAnchorRecord(assessmentId, txId, tsiHash, "DMA");
                    OutputProcessor.send(res, HttpServletResponse.SC_ACCEPTED, output);
                    break;



                case "get_assessment_list":
                    output = getAssessmentList(input, auditorId);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "get_dma_assessment_details":
                    output = getAssessmentDetails(assessmentId);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;



                case "validate_assessment":
                    output = validateAssessment(txId, tsiHash);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "submit_assessment": // Original function is renamed/replaced
                default:
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Unknown function: '" + func + "'.", req.getRequestURI());
                    break;
            }

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred: " + e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Retrieves a list of all Digital Maturity Assessments (DMA) assigned to a specific auditor.
     * The list includes relevant details from MSME and AnchorRecord tables.
     * * @param input JSON object containing "auditorId".
     * @return JSONObject containing success status and an array of assessment records.
     */
    private JSONObject getAssessmentList(JSONObject input, long auditorId) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();

        JSONArray assessmentArray = new JSONArray();
        JSONObject output = new JSONObject();

        // SQL Query to join DMA_Assessment with MSME (to get company name)
        // and LEFT JOIN AnchorRecord (to see if it's been anchored)
        String sql = "SELECT dma.\"assessmentId\", dma.\"msmeId\", dma.\"finalTsiScore\", dma.status, dma.\"completionDate\", " +
                "m.\"companyName\", " +
                "ar.\"blockchainTxId\" " +
                "FROM \"dma_assessment\" dma " +
                "JOIN \"msme\" m ON dma.\"msmeId\" = m.\"msmeId\" " +
                "LEFT JOIN \"anchor_record\" ar ON dma.\"assessmentId\" = ar.\"anchorId\" " +
                "WHERE dma.\"auditorId\" = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, auditorId);
            rs = pstmt.executeQuery();

            while (rs.next()) {
                JSONObject assessment = new JSONObject();
               

                // Fields from DMA_Assessment and MSME
                assessment.put("assessmentId", rs.getLong("assessmentId"));
                assessment.put("msmeId", rs.getLong("msmeId"));
                assessment.put("companyName", rs.getString("companyName"));
                assessment.put("type", "DMA");
                assessment.put("status", rs.getString("status"));

                // Conditional Fields
                assessment.put("finalTsiScore", rs.getObject("finalTsiScore") != null ? rs.getDouble("finalTsiScore") : null);
                assessment.put("completionDate", rs.getTimestamp("completionDate") != null ? rs.getTimestamp("completionDate").toInstant().toString() : null);

                // Anchor Status (from LEFT JOIN)
                assessment.put("isAnchored", rs.getString("blockchainTxId") != null);

                assessmentArray.add(assessment);
            }

            pool.cleanup(rs, pstmt, null);
rs = null;
pstmt = null;

String cmaSql = "SELECT \"assessmentId\", \"finalCmaScore\", status, \"completionDate\", \"assessmentDetailJson\" " +
        "FROM \"cma_assessment\" WHERE \"auditorId\" = ?";

pstmt = conn.prepareStatement(cmaSql);

            
pstmt.setLong(1, auditorId);
rs = pstmt.executeQuery();

while (rs.next()) {
    JSONObject assessment = new JSONObject();

    assessment.put("assessmentId", rs.getLong("assessmentId"));
    assessment.put("companyName", "Technology Partner");
    assessment.put("type", "CMA");
    assessment.put("status", rs.getString("status"));
    assessment.put("finalTsiScore", rs.getObject("finalCmaScore") != null ? rs.getDouble("finalCmaScore") : null);
    assessment.put("completionDate", rs.getTimestamp("completionDate") != null ? rs.getTimestamp("completionDate").toInstant().toString() : null);
    assessment.put("isAnchored", false);

    assessmentArray.add(assessment);
}

            output.put("success", true);
            output.put("data", assessmentArray);

        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return output;
    }

    // ------------------------------------------
    // New Functionality: Save and Finalize
    // ------------------------------------------

    /**
     * Saves the assessment progress, including score, answers, and qualitative input.
     * Status remains 'PENDING'.
     * @return Result JSON with the latest saved score.
     */
    private JSONObject saveAssessment(Long assessmentId, Long auditorId, JSONObject input) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        // --- 1. Extract and Validate Required Data ---
        // finalTsiScore is calculated in the backend, but we need a default/placeholder value for the DB column
        Double finalTsiScore = 0.0;
        String qualitativeNotes = (String) input.get("qualitativeNotes");
        JSONObject assessmentDetailJson = (JSONObject) input.get("assessmentDetailJson");
        Long msmeId = (Long) input.get("msmeId"); // Required for INSERT

        if (assessmentDetailJson == null) {
            result.put("error", true);
            result.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            result.put("error_message", "Incomplete Data - assessmentDetailJson is required.");
            return result;
        }

        // 2. Augment assessmentDetailJson with qualitative notes and save timestamp
        if (assessmentDetailJson != null) {
            assessmentDetailJson.put("auditorNotes", qualitativeNotes != null ? qualitativeNotes : "");
            assessmentDetailJson.put("lastSavedAt", new Timestamp(System.currentTimeMillis()).toString());
        }


        String sql = "";
        boolean isNewAssessment = (assessmentId == null || assessmentId.longValue() == 0L);

        JSONObject template = SystemConfig.readJSONTemplate("/WEB-INF/assessments/dma-v1.json");
        JSONObject results = eval(template, (JSONObject) assessmentDetailJson.get("results"));
        finalTsiScore = (double)(int) results.get("score");
        String jsonDetail = assessmentDetailJson.toJSONString();
        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            if (isNewAssessment) {
                // --- INSERT LOGIC (New Assessment) ---
                if (msmeId == null) {
                    throw new IllegalArgumentException("msmeId is required to create a new assessment.");
                }

                sql = "INSERT INTO \"dma_assessment\" (\"msmeId\", \"auditorId\", \"finalTsiScore\", \"status\", \"assessmentDetailJson\", \"completionDate\") " +
                        "VALUES (?, ?, ?, 'PENDING', ?::jsonb, NOW()) RETURNING \"assessmentId\"";

                //System.out.println("MSME Id:"+msmeId+" Auditor Id:"+auditorId);
                pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                pstmt.setLong(1, msmeId);
                pstmt.setLong(2, auditorId);
                pstmt.setDouble(3, finalTsiScore);
                pstmt.setString(4, jsonDetail);

            } else {
                // --- UPDATE LOGIC (Existing Assessment) ---
                // Only allow update if the assessment is PENDING and assigned to the correct auditor
                sql = "UPDATE \"dma_assessment\" SET \"finalTsiScore\" = ?, \"assessmentDetailJson\" = ?::jsonb, \"completionDate\" = NOW() " +
                        "WHERE \"assessmentId\" = ? AND \"auditorId\" = ? AND \"status\" = 'PENDING'";

                pstmt = conn.prepareStatement(sql);
                pstmt.setDouble(1, finalTsiScore);
                pstmt.setString(2, jsonDetail);
                pstmt.setLong(3, assessmentId);
                pstmt.setLong(4, auditorId);
            }

            int affectedRows = pstmt.executeUpdate();
            Long returnedAssessmentId = assessmentId;

            if (isNewAssessment) {
                // Retrieve the generated ID for a new insert
                rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    returnedAssessmentId = rs.getLong(1);
                } else {
                    throw new SQLException("Creating assessment failed, no ID obtained.");
                }
            } else if (affectedRows == 0) {
                // Error if updating, meaning the assessment wasn't found or wasn't PENDING
                throw new SQLException("Assessment update failed: Record not found, not assigned to auditor, or already processed.");
            }

            conn.commit(); // Commit the transaction

            result.put("success", true);
            result.put("message", isNewAssessment ? "New assessment created successfully." : "Assessment progress saved successfully.");
            result.put("assessmentId", returnedAssessmentId);
            result.put("finalTsiScore", finalTsiScore);

        } catch (IllegalArgumentException e) {
            if (conn != null) conn.rollback();
            result.put("error", true);
            result.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            result.put("error_message", e.getMessage());
            return result;
        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return new JSONObject() {{ put("success", true); put("data", result); }};
    }

    private JSONObject saveCmaAssessment(Long assessmentId, Long auditorId, JSONObject input) throws Exception {
        JSONObject assessmentDetailJson = (JSONObject) input.get("assessmentDetailJson");

        if (assessmentDetailJson == null) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", true);
            error.put("error_message", "Incomplete Data - assessmentDetailJson is required.");
            return error;
        }

        JSONObject answers = (JSONObject) assessmentDetailJson.get("results");

        if (answers == null) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", true);
            error.put("error_message", "Incomplete Data - assessmentDetailJson.results is required.");
            return error;
        }

        JSONObject template = SystemConfig.readJSONTemplate("/WEB-INF/assessments/cma-v1.json");
        JSONObject cmaResult;
        try {
            cmaResult = evaluateCma(template, answers);
        } catch (IllegalArgumentException e) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", true);
            error.put("error_message", e.getMessage());
            return error;
        }

        assessmentDetailJson.put("assessmentType", "CMA");
        assessmentDetailJson.put("type", "CMA");
        assessmentDetailJson.put("version", template.get("version"));
        assessmentDetailJson.put("totalScore", cmaResult.get("score"));
        assessmentDetailJson.put("maturitySummary", cmaResult.get("maturitySummary"));
        assessmentDetailJson.put("strengths", cmaResult.get("strengths"));
        assessmentDetailJson.put("areasForImprovement", cmaResult.get("areasForImprovement"));
        assessmentDetailJson.put("lastSavedAt", new Timestamp(System.currentTimeMillis()).toString());

        JSONObject data = new JSONObject();
        data.put("assessmentType", "CMA");
        data.put("finalCmaScore", cmaResult.get("score"));
        data.put("maturitySummary", cmaResult.get("maturitySummary"));
        data.put("strengths", cmaResult.get("strengths"));
        data.put("areasForImprovement", cmaResult.get("areasForImprovement"));
        data.put("persisted", false);

        Long savedAssessmentId = saveCmaAssessmentRecord(assessmentId, auditorId, (Integer) cmaResult.get("score"), assessmentDetailJson);
        if (savedAssessmentId != null) {
            data.put("assessmentId", savedAssessmentId);
            data.put("persisted", true);
        }

        JSONObject output = new JSONObject();
        output.put("success", true);
        output.put("message", "CMA assessment saved successfully.");
        output.put("data", data);
        return output;
    }

    private JSONObject evaluateCma(JSONObject template, JSONObject answers) {
        JSONObject result = new JSONObject();
        JSONArray strengths = new JSONArray();
        JSONArray improvementAreas = new JSONArray();
        JSONArray sections = (JSONArray) template.get("sections");
        int score = 0;

        Iterator<JSONObject> sectionIt = sections.iterator();
        while (sectionIt.hasNext()) {
            JSONObject section = sectionIt.next();
            String sectionTitle = (String) section.get("sectionTitle");
            JSONArray questions = (JSONArray) section.get("questions");
            int sectionScore = 0;
            int sectionCount = 0;

            Iterator<JSONObject> questionIt = questions.iterator();
            while (questionIt.hasNext()) {
                JSONObject question = questionIt.next();
                String questionId = (String) question.get("questionId");
                int answer = getCmaAnswerValue(answers, questionId);
                score += answer;
                sectionScore += answer;
                sectionCount++;
            }

            double sectionAverage = (double) sectionScore / sectionCount;
            if (sectionAverage >= 4.0) {
                strengths.add(sectionTitle);
            }
            if (sectionAverage <= 2.0) {
                improvementAreas.add(sectionTitle);
            }
        }

        result.put("score", score);
        result.put("maturitySummary", getCmaMaturitySummary(score));
        result.put("strengths", strengths);
        result.put("areasForImprovement", improvementAreas);
        return result;
    }

    private int getCmaAnswerValue(JSONObject answers, String questionId) {
        Object value = answers.get(questionId);

        if (value == null) {
            throw new IllegalArgumentException("Missing CMA answer for question: " + questionId);
        }

        int answer;
        try {
            answer = Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CMA answer for question: " + questionId);
        }

        if (answer < 1 || answer > 5) {
            throw new IllegalArgumentException("CMA answer must be between 1 and 5 for question: " + questionId);
        }

        return answer;
    }

    private String getCmaMaturitySummary(int score) {
        if (score <= 24) return "Level 1 \u2013 Ad-Hoc / Initial";
        if (score <= 36) return "Level 2 \u2013 Managed / Basic";
        if (score <= 48) return "Level 3 \u2013 Defined / Developing";
        if (score <= 59) return "Level 4 \u2013 Quantitatively Managed / Consistent";
        return "Level 5 \u2013 Optimizing / Leading";
    }

    private Long saveCmaAssessmentRecord(Long assessmentId, Long auditorId, Integer finalCmaScore, JSONObject assessmentDetailJson) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        boolean isExistingAssessment = assessmentId != null && assessmentId.longValue() > 0L;

        try {
            conn = pool.getConnection();
            DatabaseMetaData metaData = conn.getMetaData();
            rs = metaData.getTables(null, null, "cma_assessment", new String[]{"TABLE"});

            if (!rs.next()) {
                return null;
            }

            pool.cleanup(rs, null, null);
            rs = null;

            if (isExistingAssessment) {
                String sql = "UPDATE \"cma_assessment\" SET \"finalCmaScore\" = ?, \"assessmentDetailJson\" = ?::jsonb, \"completionDate\" = NOW() " +
                        "WHERE \"assessmentId\" = ? AND \"auditorId\" = ? AND status = 'PENDING'";

                pstmt = conn.prepareStatement(sql);
                pstmt.setDouble(1, finalCmaScore);
                pstmt.setString(2, assessmentDetailJson.toJSONString());
                pstmt.setLong(3, assessmentId);
                pstmt.setLong(4, auditorId);

                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("CMA assessment update failed: Record not found, not assigned to auditor, or already published.");
                }
                return assessmentId;
            }

            String sql = "INSERT INTO \"cma_assessment\" (\"auditorId\", \"finalCmaScore\", \"status\", \"assessmentDetailJson\", \"completionDate\") " +
                    "VALUES (?, ?, 'PENDING', ?::jsonb, NOW()) RETURNING \"assessmentId\"";

            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, auditorId);
            pstmt.setDouble(2, finalCmaScore);
            pstmt.setString(3, assessmentDetailJson.toJSONString());

            rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("assessmentId");
            }
            return null;
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            if ("42P01".equals(sqlState) || "42703".equals(sqlState)) {
                return null;
            }
            throw e;
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
    }

    private JSONObject getCmaAssessmentDetails(Long assessmentId, Long auditorId) throws Exception {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        if (assessmentId == null || assessmentId.longValue() <= 0) {
            result.put("success", false);
            result.put("error", true);
            result.put("error_message", "Invalid CMA assessment ID provided.");
            return result;
        }

        String sql = "SELECT cma.\"assessmentId\", cma.\"finalCmaScore\", cma.status, cma.\"completionDate\", cma.\"assessmentDetailJson\", " +
                "u.email AS auditor_email, u.\"linkedin\" " +
                "FROM \"cma_assessment\" cma " +
                "JOIN \"users\" u ON cma.\"auditorId\" = u.\"userId\" " +
                "WHERE cma.\"assessmentId\" = ? AND cma.\"auditorId\" = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, assessmentId);
            pstmt.setLong(2, auditorId);
            rs = pstmt.executeQuery();

            if (!rs.next()) {
                throw new SQLException("CMA assessment not found for ID: " + assessmentId);
            }

            JSONObject data = new JSONObject();
            data.put("assessmentId", rs.getLong("assessmentId"));
            data.put("assessmentType", "CMA");
            data.put("type", "CMA");
            data.put("status", rs.getString("status"));
            double finalCmaScore = rs.getObject("finalCmaScore") != null ? rs.getDouble("finalCmaScore") : 0.0;
            String completionDate = rs.getTimestamp("completionDate") != null ? rs.getTimestamp("completionDate").toInstant().toString() : null;
            data.put("finalCmaScore", finalCmaScore);
            data.put("finalTsiScore", finalCmaScore);
            data.put("completionDate", completionDate);
            data.put("msmeName", "Technology Partner");
            data.put("udyamRegistrationNo", "CMA");
            data.put("auditorEmail", rs.getString("auditor_email"));
            data.put("auditorLinkedin", rs.getString("linkedin"));

            String assessmentDetailJsonString = rs.getString("assessmentDetailJson");
            if (assessmentDetailJsonString != null) {
                data.put("assessmentDetailJson", new JSONParser().parse(assessmentDetailJsonString));
            }

            result.put("success", true);
            result.put("data", data);
            return result;
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
    }

    private JSONObject publishCmaAssessment(Long assessmentId, Long auditorId) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        if (assessmentId == null || assessmentId.longValue() <= 0) {
            result.put("success", false);
            result.put("error", true);
            result.put("error_message", "Invalid CMA assessment ID provided.");
            return result;
        }

        String sql = "UPDATE \"cma_assessment\" SET status = 'AUDITED', \"completionDate\" = NOW() " +
                "WHERE \"assessmentId\" = ? AND \"auditorId\" = ? AND status = 'PENDING'";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, assessmentId);
            pstmt.setLong(2, auditorId);

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                result.put("success", false);
                result.put("error", true);
                result.put("error_message", "CMA publish failed: Record not found, not assigned to auditor, or already published.");
                return result;
            }

            result.put("success", true);
            result.put("message", "CMA assessment published successfully.");
            return result;
        } finally {
            pool.cleanup(null, pstmt, conn);
        }
    }

    // ------------------------------------------
    // Existing/Helper Functions (Moved/Simplified)
    // ------------------------------------------

    private JSONObject eval(JSONObject template, JSONObject data){
        JSONObject results = new JSONObject();
        JSONArray sections = null;
        String sectionTitle = null;
        Iterator<JSONObject> sectionIt = null;
        JSONObject sectionOb = null;
        JSONArray questions = null;
        Iterator<JSONObject> questionIt = null;
        JSONObject questionOb = null;
        String qid = null;
        String value = null;
        int valueInt = 0;
        int score = 0;
        int sectionScore = 0;
        int sectionCount = 0;
        float sectionAvg = 0f;
        String assessmentType = null;
        JSONArray strengths = new JSONArray();
        JSONArray weaknesses = new JSONArray();

        assessmentType = (String) template.get("assessment_type");
        sections = (JSONArray) template.get("sections");
        sectionIt = sections.iterator();
        while(sectionIt.hasNext()){
            sectionOb = (JSONObject) sectionIt.next();
            sectionTitle = (String) sectionOb.get("sectionTitle");
            questions = (JSONArray) sectionOb.get("questions");
            questionIt = questions.iterator();
            sectionScore = 0;sectionCount = 0;
            while(questionIt.hasNext()){
                questionOb = (JSONObject) questionIt.next();
                qid = (String) questionOb.get("questionId");
                valueInt =  (int)(long) data.get(qid);
                score += valueInt;
                sectionCount++;
                sectionScore += valueInt;
            }
            sectionAvg = sectionScore/sectionCount;
            if(sectionAvg>=4){
                strengths.add(sectionTitle);
            }else if(sectionAvg<=2){
                weaknesses.add(sectionTitle);
            }
        }

        if(score<=28){
                results.put("rating",1);
                results.put("rating_summary","Level 1: Nascent");
                results.put("score",score);
                results.put("description","The organisation has very limited digital adoption, largely relies on manual processes, and lacks a clear digital strategy. Digital tools, if used, are isolated and reactive. Awareness of digital potential is low.");
                results.put("characteristics","Primarily offline, basic communication, no structured data, high manual effort, low digital skill set.");
                results.put("strengths",strengths);
                results.put("areas_for_improvement",weaknesses);
                results.put("recommendation", "Focus on foundational digital literacy, basic online presence (GMB, social media), and initial digitization of core administrative tasks.");
            }else if(score<=41){
                results.put("rating",2);
                results.put("rating_summary","Level 2: Emerging");
                results.put("score",score);
                results.put("description","The organisation has begun to adopt some digital tools, often in an uncoordinated manner. There's an awareness of digital benefits, but no cohesive strategy. Processes might be partially digitized.");
                results.put("characteristics","Some online presence, basic use of digital communication, fragmented data, growing but inconsistent use of software.");
                results.put("strengths",strengths);
                results.put("areas_for_improvement",weaknesses);
                results.put("recommendation", "Develop a simple digital roadmap, explore cloud tools for efficiency, establish basic cybersecurity, and offer introductory digital training.");
            }else if(score<=54){
                results.put("rating",3);
                results.put("rating_summary","Level 3: Developing");
                results.put("score",score);
                results.put("description","The organisation is actively integrating digital tools into various aspects of its business. There's a nascent digital strategy, and some processes are becoming more efficient. Data collection exists but analysis might be limited.");
                results.put("characteristics","Dedicated website, basic digital marketing, some internal collaboration tools, moderate digital skills, conscious of data.");
                results.put("strengths",strengths);
                results.put("areas_for_improvement",weaknesses);
                results.put("recommendation", "Integrate existing digital tools, explore CRM/ERP Lite, implement structured digital marketing, and invest in targeted skill development.");
            }else if(score<=67){
                results.put("rating",4);
                results.put("rating_summary","Level 4: Mature");
                results.put("score",score);
                results.put("description","The organisation leverages digital technologies strategically across most functions. They have a clear digital vision, data-driven decision-making is emerging, and cybersecurity is prioritized.");
                results.put("characteristics","Strong online presence, active digital marketing, automated key processes, data-driven insights, proactive cybersecurity, good digital culture.");
                results.put("strengths",strengths);
                results.put("areas_for_improvement",weaknesses);
                results.put("recommendation", "Optimize digital processes, explore advanced analytics, consider AI/automation pilot projects, and foster continuous digital innovation.");
            }else{
                results.put("rating",5);
                results.put("rating_summary","Level 5: Advanced");
                results.put("score",score);
                results.put("description","The organisation is a digitally transformed entity, continuously innovating and leveraging technology to gain a competitive advantage. Digital is ingrained in its culture, strategy, and operations.");
                results.put("characteristics","Digital-first approach, highly automated and integrated systems, advanced analytics, personalized customer experiences, robust cybersecurity, continuous digital learning and adaptation.");
                results.put("strengths",strengths);
                results.put("areas_for_improvement",weaknesses);
                results.put("recommendation", " Explore disruptive technologies, engage in industry leadership, develop digital products/services, and benchmark against global best practices.");
        }
        return results;
    }

    /**
     * Retrieves the latest DMA questionnaire structure.
     */
    private JSONObject getQuestionnaire() throws ParseException {
        // Renamed from getQuestionnaireTemplate
        JSONObject assessment = SystemConfig.readJSONTemplate("/WEB-INF/assessments/"+"dma"+"-"+"v1"+".json");
        return new JSONObject() {{ put("success", true); put("data", assessment); }};
    }
   
    private JSONObject getCmaQuestionnaire() throws ParseException {
    JSONObject assessment = SystemConfig.readJSONTemplate("/WEB-INF/assessments/cma-v1.json");

    return new JSONObject() {{
        put("success", true);
        put("data", assessment);
    }};
}
    /**
     * Returns the lightweight CMA scaffold status for the auditor dashboard preview.
     */
    private JSONObject getCmaStatus() {
        JSONObject status = new JSONObject();
        status.put("success", true);
        status.put("module", "CMA");
        status.put("title", "Capability Maturity Assessment");
        status.put("description", "CMA evaluates local technology partners, implementers, and integrators for service readiness, implementation capability, and trust.");
        status.put("currentStage", "Scaffold ready");
        return status;
    }

    /**
     * [HELPER] Inserts the blockchain anchor proof into the AnchorRecord table and updates DMA_Assessment status.
     */
    private JSONObject updateAnchorRecord(Long assessmentId, String txId, String tsiHash, String type) throws SQLException {
        // ... (implementation remains the same as provided in the previous turn)
        Connection conn = null;
        PreparedStatement pstmtAnchor = null;
        PreparedStatement pstmtUpdate = null;
        PoolDB pool = new PoolDB();

        // 1. Insert into AnchorRecord
        String sqlAnchor = "INSERT INTO \"anchor_record\" (\"anchorId\", \"type\", \"blockchainTxId\", \"tsiHash\", \"anchorDate\", \"blockchainNetwork\") VALUES (?, ?, ?, ?, NOW(), 'TSI-Ledger')";
        // 2. Update DMA_Assessment status
        String sqlUpdate = "UPDATE \"dma_assessment\" SET status = 'ANCHORED' WHERE \"assessmentId\" = ?";

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);
            pstmtAnchor = conn.prepareStatement(sqlAnchor);
            pstmtAnchor.setLong(1, assessmentId);
            pstmtAnchor.setString(2, type);
            pstmtAnchor.setString(3, txId);
            pstmtAnchor.setString(4, tsiHash);
            pstmtAnchor.executeUpdate();

            pstmtUpdate = conn.prepareStatement(sqlUpdate);
            pstmtUpdate.setLong(1, assessmentId);
            pstmtUpdate.executeUpdate();

            conn.commit();
        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            pool.cleanup(null, pstmtAnchor, null);
            pool.cleanup(null, pstmtUpdate, conn);
        }
        return new JSONObject() {{ put("success", true);}};
    }

    /**
     * Helper to safely get a Double from a JSONObject.
     */
    private Double getDoubleOrNull(Object obj) {
        if (obj == null) return null;
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Retrieves the comprehensive details of a single DMA assessment.
     * The list includes data from the DMA_Assessment table, the MSME (company name),
     * the User (auditor email), and the AnchorRecord (blockchain proof) if available.
     */
    private JSONObject getAssessmentDetails(Long assessmentId) throws Exception {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        if (assessmentId == null || assessmentId.longValue() <= 0) {
            result.put("error", true);
            result.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            result.put("error_message", "Invalid Assessment ID provided.");
            return result;
        }

        // SQL Query to join DMA_Assessment with MSME, User (Auditor), and LEFT JOIN AnchorRecord
        String sql = "SELECT dma.\"assessmentId\", dma.\"msmeId\", dma.\"finalTsiScore\", dma.status, dma.\"completionDate\", dma.\"requestFormJson\", dma.\"assessmentDetailJson\", " +
                "ar.\"blockchainTxId\", ar.\"tsiHash\", ar.\"anchorDate\", " +
                "u.email AS auditor_email, m.\"companyName\" AS msme_name, m.\"udyamRegistrationNo\", u.\"linkedin\" " +
                "FROM \"dma_assessment\" dma " +
                "JOIN \"users\" u ON dma.\"auditorId\" = u.\"userId\" " +
                "JOIN \"msme\" m ON dma.\"msmeId\" = m.\"msmeId\" " +
                "LEFT JOIN \"anchor_record\" ar ON dma.\"assessmentId\" = ar.\"anchorId\" " +
                "WHERE dma.\"assessmentId\" = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, assessmentId);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                double score = rs.getObject("finalTsiScore") != null ? rs.getDouble("finalTsiScore") : 0.0;
                String completionDate = rs.getTimestamp("completionDate") != null ? rs.getTimestamp("completionDate").toInstant().toString() : null;

                // --- Core Assessment Data ---
                result.put("assessmentId", rs.getLong("assessmentId"));
                result.put("msmeId", rs.getLong("msmeId"));
                result.put("finalTsiScore", score );
                result.put("status", rs.getString("status"));
                result.put("completionDate", completionDate);

                // --- MSME & Auditor Data ---
                result.put("msmeName", rs.getString("msme_name"));
                result.put("udyamRegistrationNo", rs.getString("udyamRegistrationNo"));
                result.put("auditorEmail", rs.getString("auditor_email"));
                result.put("auditorLinkedin", rs.getString("linkedin"));

                // --- Assessment Payload Data (JSONB) ---
                String assessmentDetailJsonString = rs.getString("assessmentDetailJson");
                if (assessmentDetailJsonString != null) {
                     result.put("assessmentDetailJson", new JSONParser().parse(assessmentDetailJsonString));
                    //result.put("assessmentDetailJson", assessmentDetailJsonString);
                }

                // --- Anchor Record Data (Proof of Immutability) ---
                if (rs.getString("blockchainTxId") != null) {
                    JSONObject anchor = new JSONObject();
                    result.put("blockchainTxId", rs.getString("blockchainTxId"));
                    result.put("tsiHash", rs.getString("tsiHash"));
                    result.put("anchorDate", rs.getTimestamp("anchorDate").toInstant().toString());
                }else{
                    result.put("tsiHash", generateAssessmentString( assessmentId,
                                                                    score,
                                                                    completionDate));
                }
            } else {
                throw new SQLException("Assessment not found for ID: " + assessmentId);
            }

        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return new JSONObject() {{ put("success", true); put("data", result); }};
    }

    /**
     * Generates a concatenated string from MSME assessment data.
     */
    private static String generateAssessmentString(
            Object assessmentId,
            Object finalScore,
            Object assessmentDate) {

        final String DELIMITER = "|";

        return String.valueOf(assessmentId)
                + DELIMITER
                + String.valueOf(finalScore)
                + DELIMITER
                + String.valueOf(assessmentDate);
    }

    private JSONObject validateAssessment(String txId,String tsiHash) throws Exception {
        JSONObject result =  new JSONObject();
        try {
            result = new BSVUtil().validateAssessment(txId, tsiHash);
        }catch(Exception e){
            result.put("failed",true);
        }
        return result;
    }



    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        if (!"POST".equalsIgnoreCase(method)) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed", "Only POST method is supported.", req.getRequestURI());
            return false;
        }
        return InputProcessor.validate(req, res);
    }
}
