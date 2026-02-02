package com.igot.cb.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.cache.RedisCacheMgr;
import com.igot.cb.cassandra.utils.CassandraOperation;
import com.igot.cb.common.model.SBApiResponse;
import com.igot.cb.common.service.ContentService;
import com.igot.cb.common.service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.common.util.CbExtAssessmentServerProperties;
import com.igot.cb.common.util.Constants;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;


import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.igot.cb.common.util.ProjectUtil.updateErrorDetails;

@Service
public class AssessmentUtilServiceV2Impl implements AssessmentUtilServiceV2 {

	@Autowired
	CbExtAssessmentServerProperties serverProperties;

	@Autowired
	OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

	@Autowired
	ObjectMapper mapper;

	@Autowired
	CassandraOperation cassandraOperation;

	private Logger logger = LoggerFactory.getLogger(AssessmentUtilServiceV2Impl.class);

	@Autowired
	RedisCacheMgr redisCacheMgr;

	@Autowired
	ContentService contentService;

	public Map<String, Object> validateQumlAssessment(List<String> originalQuestionList,
													  List<Map<String, Object>> userQuestionList,Map<String,Object> questionMap) {
		try {
			Integer correct = 0;
			Integer blank = 0;
			Integer inCorrect = 0;
			Double result;
			Integer total = 0;
			Map<String, Object> resultMap = new HashMap<>();
			Map<String, Object> answers = getQumlAnswers(originalQuestionList,questionMap);
			for (Map<String, Object> question : userQuestionList) {
				List<String> marked = new ArrayList<>();
				if (question.containsKey(Constants.QUESTION_TYPE)) {
					String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
					Map<String, Object> editorStateObj = (Map<String, Object>) question.get(Constants.EDITOR_STATE);
					List<Map<String, Object>> options = (List<Map<String, Object>>) editorStateObj
							.get(Constants.OPTIONS);
					switch (questionType) {
						case Constants.MTF:
							for (Map<String, Object> option : options) {
								marked.add(option.get(Constants.INDEX).toString() + "-"
										+ option.get(Constants.SELECTED_ANSWER).toString().toLowerCase());
							}
							break;
						case Constants.FTB:
							for (Map<String, Object> option : options) {
								marked.add((String) option.get(Constants.SELECTED_ANSWER));
							}
							break;
						case Constants.MCQ_SCA:
						case Constants.MCQ_MCA:
							for (Map<String, Object> option : options) {
								if ((boolean) option.get(Constants.SELECTED_ANSWER)) {
									marked.add((String) option.get(Constants.INDEX));
								}
							}
							break;
						default:
							break;
					}
				}
				if (CollectionUtils.isEmpty(marked)){
					blank++;
					question.put(Constants.RESULT,Constants.BLANK);
				}
				else {
					List<String> answer = (List<String>) answers.get(question.get(Constants.IDENTIFIER));
					if (answer.size() > 1)
						Collections.sort(answer);
					if (marked.size() > 1)
						Collections.sort(marked);
					if (answer.equals(marked)){
					    question.put(Constants.RESULT,Constants.CORRECT);
						correct++;
					}
					else{
						question.put(Constants.RESULT,Constants.INCORRECT);
						inCorrect++;
					}
				}
			}
			// Increment the blank counter for skipped question objects
			if (answers.size() > userQuestionList.size()) {
				blank += answers.size() - userQuestionList.size();
			}
			total = correct + blank + inCorrect;
			resultMap.put(Constants.RESULT, total == 0 ? 0 : ((correct * 100d) / total));
			resultMap.put(Constants.INCORRECT, inCorrect);
			resultMap.put(Constants.BLANK, blank);
			resultMap.put(Constants.CORRECT, correct);
			resultMap.put(Constants.TOTAL, total);
			resultMap.put(Constants.CHILDREN,userQuestionList);
			return resultMap;

		} catch (Exception ex) {
			logger.error("Error when verifying assessment. Error : ", ex);
		}
		return new HashMap<>();
	}

	private Map<String, Object> getQumlAnswers(List<String> questions,Map<String,Object> questionMap) throws Exception {
		Map<String, Object> ret = new HashMap<>();

		//Map<String, Map<String, Object>> questionMap = new HashMap<String, Map<String, Object>>();

		for (String questionId : questions) {
			List<String> correctOption = new ArrayList<>();
			Map<String, Object> question = (Map<String, Object>) questionMap.get(questionId);
			if (MapUtils.isEmpty(question)) {
				ret.put(questionId, correctOption);
				continue;
			}
			if (question.containsKey(Constants.QUESTION_TYPE)) {
				String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
				Map<String, Object> editorStateObj = (Map<String, Object>) question.get(Constants.EDITOR_STATE);
				if (MapUtils.isEmpty(editorStateObj)) {
					ret.put(question.get(Constants.IDENTIFIER).toString(), correctOption);
					continue;
				}
				List<Map<String, Object>> options = (List<Map<String, Object>>) editorStateObj.get(Constants.OPTIONS);
				if (CollectionUtils.isEmpty(options)) {
					ret.put(question.get(Constants.IDENTIFIER).toString(), correctOption);
					continue;
				}
				switch (questionType) {
					case Constants.MTF:
						for (Map<String, Object> option : options) {
							Map<String, Object> valueObj = (Map<String, Object>) option.get(Constants.VALUE);
							if (MapUtils.isNotEmpty(valueObj) && valueObj.get(Constants.VALUE) != null && option.get(Constants.ANSWER) != null) {
								correctOption.add(valueObj.get(Constants.VALUE).toString() + "-"
										+ option.get(Constants.ANSWER).toString().toLowerCase());
							}
						}
						break;
					case Constants.FTB:
						for (Map<String, Object> option : options) {
							if (Boolean.TRUE.equals(option.get(Constants.ANSWER))) {
								Map<String, Object> valueObj = (Map<String, Object>) option.get(Constants.VALUE);
								if (MapUtils.isNotEmpty(valueObj) && valueObj.get(Constants.BODY) != null) {
									correctOption.add(valueObj.get(Constants.BODY).toString());
								}
							}
						}
						break;
					case Constants.MCQ_SCA:
					case Constants.MCQ_MCA:
					case Constants.MCQ_SCA_TF:
						for (Map<String, Object> option : options) {
							if (Boolean.TRUE.equals(option.get(Constants.ANSWER))) {
								Map<String, Object> valueObj = (Map<String, Object>) option.get(Constants.VALUE);
								if (MapUtils.isNotEmpty(valueObj) && valueObj.get(Constants.VALUE) != null) {
									correctOption.add(valueObj.get(Constants.VALUE).toString());
								}
							}
						}
						break;
					default:
						break;
				}
				ret.put(question.get(Constants.IDENTIFIER).toString(), correctOption);
			} else {
				List<Map<String, Object>> options = (List<Map<String, Object>>) question.get(Constants.OPTIONS);
				if (!CollectionUtils.isEmpty(options)) {
					for (Map<String, Object> opt : options) {
						if (Boolean.TRUE.equals(opt.get(Constants.IS_CORRECT)) && opt.get(Constants.OPTION_ID) != null)
							correctOption.add(opt.get(Constants.OPTION_ID).toString());
					}
				}
				ret.put(question.get(Constants.IDENTIFIER) != null ? question.get(Constants.IDENTIFIER).toString() : questionId, correctOption);
			}
		}

		return ret;
	}

	private Map<String, Map<String, Object>> fetchQuestionMapDetails(String questionId) {
		// Taking the list which was formed with the not found values in Redis, we are
		// making an internal POST call to Question List API to fetch the details
		Map<String, Map<String, Object>> questionsMap = new HashMap<>();
		List<Map<String, Object>> questionMapList = readQuestionDetails(Collections.singletonList(questionId));
		for (Map<String, Object> questionMapResponse : questionMapList) {
			if (!ObjectUtils.isEmpty(questionMapResponse)
					&& Constants.OK.equalsIgnoreCase((String) questionMapResponse.get(Constants.RESPONSE_CODE))) {
				List<Map<String, Object>> questionMap = ((List<Map<String, Object>>) ((Map<String, Object>) questionMapResponse
						.get(Constants.RESULT)).get(Constants.QUESTIONS));
				for (Map<String, Object> question : questionMap) {
					if (!ObjectUtils.isEmpty(questionMap)) {
						questionsMap.put((String) question.get(Constants.IDENTIFIER), question);
					}
				}
			}
		}
		return questionsMap;
	}

	@Override
	public String fetchQuestionIdentifierValue(List<String> identifierList, List<Object> questionList,
			String primaryCategory)
			throws Exception {
		List<String> newIdentifierList = new ArrayList<>();
		newIdentifierList.addAll(identifierList);
		String errMsg = "";

		// Taking the list which was formed with the not found values in Redis, we are
		// making an internal POST call to Question List API to fetch the details
		if (!newIdentifierList.isEmpty()) {
			List<Map<String, Object>> questionMapList = readQuestionDetails(newIdentifierList);
			for (Map<String, Object> questionMapResponse : questionMapList) {
				if (!ObjectUtils.isEmpty(questionMapResponse)
						&& Constants.OK.equalsIgnoreCase((String) questionMapResponse.get(Constants.RESPONSE_CODE))) {
					List<Map<String, Object>> questionMap = ((List<Map<String, Object>>) ((Map<String, Object>) questionMapResponse
							.get(Constants.RESULT)).get(Constants.QUESTIONS));
					for (Map<String, Object> question : questionMap) {
						if (!ObjectUtils.isEmpty(questionMap)) {
							questionList.add(filterQuestionMapDetail(question, primaryCategory));
						} else {
							errMsg = String.format("Failed to get Question Details for Id: %s",
									question.get(Constants.IDENTIFIER).toString());
							logger.error(errMsg);
							return errMsg;
						}
					}
				} else {
					errMsg = String.format("Failed to get Question Details from the Question List API for the IDs: %s",
									newIdentifierList.toString());
					logger.error(errMsg);
					return errMsg;
				}
			}
		}
		return "";
	}

	@Override
	public Map<String, Object> filterQuestionMapDetail(Map<String, Object> questionMapResponse,
			String primaryCategory) {
		List<String> questionParams = serverProperties.getAssessmentQuestionParams();
		Map<String, Object> updatedQuestionMap = new HashMap<>();
		for (String questionParam : questionParams) {
			if (questionMapResponse.containsKey(questionParam)) {
				updatedQuestionMap.put(questionParam, questionMapResponse.get(questionParam));
			}
		}
		if (questionMapResponse.containsKey(Constants.EDITOR_STATE)
				&& primaryCategory.equalsIgnoreCase(Constants.PRACTICE_QUESTION_SET)) {
			Map<String, Object> editorState = (Map<String, Object>) questionMapResponse.get(Constants.EDITOR_STATE);
			updatedQuestionMap.put(Constants.EDITOR_STATE, editorState);
		}
		if (questionMapResponse.containsKey(Constants.CHOICES)
				&& updatedQuestionMap.containsKey(Constants.PRIMARY_CATEGORY) && !updatedQuestionMap
						.get(Constants.PRIMARY_CATEGORY).toString().equalsIgnoreCase(Constants.FTB_QUESTION)) {
			Map<String, Object> choicesObj = (Map<String, Object>) questionMapResponse.get(Constants.CHOICES);
			Map<String, Object> updatedChoicesMap = new HashMap<>();
			if (choicesObj.containsKey(Constants.OPTIONS)) {
				List<Map<String, Object>> optionsMapList = (List<Map<String, Object>>) choicesObj
						.get(Constants.OPTIONS);
				updatedChoicesMap.put(Constants.OPTIONS, shuffleOptions(optionsMapList));
			}
			updatedQuestionMap.put(Constants.CHOICES, updatedChoicesMap);
		}
		if (questionMapResponse.containsKey(Constants.RHS_CHOICES)
				&& updatedQuestionMap.containsKey(Constants.PRIMARY_CATEGORY) && updatedQuestionMap
						.get(Constants.PRIMARY_CATEGORY).toString().equalsIgnoreCase(Constants.MTF_QUESTION)) {
			List<Object> rhsChoicesObj = (List<Object>) questionMapResponse.get(Constants.RHS_CHOICES);
			Collections.shuffle(rhsChoicesObj);
			updatedQuestionMap.put(Constants.RHS_CHOICES, rhsChoicesObj);
		}

		return updatedQuestionMap;
	}

	@Override
	public List<Map<String, Object>> readQuestionDetails(List<String> identifiers) {
		try {
			StringBuilder sbUrl = new StringBuilder(serverProperties.getAssessmentHost());
			sbUrl.append(serverProperties.getAssessmentQuestionListPath());
			Map<String, String> headers = new HashMap<>();
			headers.put(Constants.AUTHORIZATION, serverProperties.getSbApiKey());
			Map<String, Object> requestBody = new HashMap<>();
			Map<String, Object> requestData = new HashMap<>();
			Map<String, Object> searchData = new HashMap<>();
			requestData.put(Constants.SEARCH, searchData);
			requestBody.put(Constants.REQUEST, requestData);
			List<Map<String, Object>> questionDataList = new ArrayList<>();
			int chunkSize = 15;
			for (int i = 0; i < identifiers.size(); i += chunkSize) {
				List<String> identifierList;
				if ((i + chunkSize) >= identifiers.size()) {
					identifierList = identifiers.subList(i, identifiers.size());
				} else {
					identifierList = identifiers.subList(i, i + chunkSize);
				}
				searchData.put(Constants.IDENTIFIER, identifierList);
				Map<String, Object> data = outboundRequestHandlerService.fetchResultUsingPost(sbUrl.toString(),
						requestBody, headers);
				if (!ObjectUtils.isEmpty(data)) {
					questionDataList.add(data);
				}
			}
			return questionDataList;
		} catch (Exception e) {
			logger.info(String.format("Failed to process the readQuestionDetails. %s", e.getMessage()));
		}
		return new ArrayList<>();
	}

	@Override
	public Map<String, Object> getReadHierarchyApiResponse(String assessmentIdentifier, String token) {
		try {
			StringBuilder sbUrl = new StringBuilder(serverProperties.getAssessmentHost());
			sbUrl.append(serverProperties.getAssessmentHierarchyReadPath());
			String serviceURL = sbUrl.toString().replace(Constants.IDENTIFIER_REPLACER, assessmentIdentifier);
			Map<String, String> headers = new HashMap<>();
			headers.put(Constants.X_AUTH_TOKEN, token);
			headers.put(Constants.AUTHORIZATION, serverProperties.getSbApiKey());
			Object o = outboundRequestHandlerService.fetchUsingGetWithHeaders(serviceURL, headers);
			Map<String, Object> data = new ObjectMapper().convertValue(o, Map.class);
			return data;
		} catch (Exception e) {
			logger.error("error in getReadHierarchyApiResponse  " + e.getMessage(), e);
		}
		return new HashMap<>();
	}

	public Map<String,Object> fetchHierarchyFromAssessServc(String qSetId,String token){
			Map<String, Object> readHierarchyApiResponse = getReadHierarchyApiResponse(qSetId, token);
			if (!readHierarchyApiResponse.isEmpty())
				if (ObjectUtils.isEmpty(readHierarchyApiResponse) || !Constants.OK.equalsIgnoreCase((String) readHierarchyApiResponse.get(Constants.RESPONSE_CODE))) {
					throw new RuntimeException("Internal Server Error");
				}
			return ((Map<String, Object>) ((Map<String, Object>) readHierarchyApiResponse.get(Constants.RESULT)).get(Constants.QUESTION_SET));
	}

	public Map<String, Object> readAssessmentHierarchyFromCache(String assessmentIdentifier,boolean editMode,String token) {

		if(editMode)
		return fetchHierarchyFromAssessServc(assessmentIdentifier,token);

		String questStr = Constants.EMPTY;
		if(serverProperties.qListFromCacheEnabled()) {
			 questStr = redisCacheMgr.getCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTION_SET);		
		}
		if(StringUtils.isEmpty(questStr)) {
			Map<String, Object> propertyMap = new HashMap<String, Object>();
		propertyMap.put(Constants.IDENTIFIER, assessmentIdentifier);
		List<Map<String, Object>> hierarchyList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
				serverProperties.getAssessmentHierarchyNameSpace(),
				serverProperties.getAssessmentHierarchyTable(), propertyMap, null);
		if (!CollectionUtils.isEmpty(hierarchyList)) {
			Map<String, Object> assessmentEntry = hierarchyList.get(0);
			String hierarchyStr = (String) assessmentEntry.get(Constants.HIERARCHY);
			if (StringUtils.isNotBlank(hierarchyStr)) {
				try {
					Map<String,Object> questionHierarchy = mapper.readValue(hierarchyStr, new TypeReference<Map<String, Object>>() {
					});
					redisCacheMgr.putCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTION_SET, questionHierarchy,serverProperties.getRedisQuestionsReadTimeOut().intValue());
					return questionHierarchy;
				} catch (Exception e) {
					logger.error("Failed to read hierarchy data. Exception: ", e);
				}
			}
		}
		}
		else {
			try {
				return mapper.readValue(questStr, new TypeReference<Map<String, Object>>() {
				});
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		return MapUtils.EMPTY_SORTED_MAP;
	}

	public List<Map<String, Object>> readUserSubmittedAssessmentRecords(String userId, String assessmentId) {
		Map<String, Object> propertyMap = new HashMap<String, Object>();
		propertyMap.put(Constants.USER_ID, userId);
		propertyMap.put(Constants.ASSESSMENT_ID_KEY, assessmentId);
		return cassandraOperation.getRecordsByPropertiesWithoutFiltering(
				Constants.SUNBIRD_KEY_SPACE_NAME, serverProperties.getAssessmentUserSubmitDataTable(),
				propertyMap, null);
	}


	public Map<String, Object> readQListfromCache(List<String> questionIds, String assessmentIdentifier,boolean editMode,String token) throws IOException {
		if (serverProperties.qListFromCacheEnabled() && !editMode)
			return qListFromCache(assessmentIdentifier,editMode,token);
		else
			return qListFrmAssessService(questionIds);
	}

	private Map<String, Object> qListFrmAssessService(List<String> questionIds) {
		// Read question details for the fetched question IDs
		List<Map<String, Object>> questionResultsList = readQuestionDetails(questionIds);
		if (CollectionUtils.isEmpty(questionResultsList)) return new HashMap<>();
		// Construct the question map
		return questionResultsList.stream()
				.flatMap(questList -> ((List<Map<String, Object>>) ((Map<String, Object>) questList.get(Constants.RESULT)).get(Constants.QUESTIONS)).stream())
				.collect(Collectors.toMap(question -> (String) question.get(Constants.IDENTIFIER), question -> question));
	}

	public Map<String, Object> qListFromCache(String assessmentIdentifier,boolean editMode,String token) throws IOException {
		String questStr = redisCacheMgr.getCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTIONS);
		if (StringUtils.isEmpty(questStr)) {
			Map<String, Object> questionMap = new HashMap<>();
			// Read assessment hierarchy from DB
			Map<String, Object> assessmentData = readAssessmentHierarchyFromCache(assessmentIdentifier,editMode,token);
			if (CollectionUtils.isEmpty(assessmentData)) return questionMap;
			List<Map<String, Object>> children = (List<Map<String, Object>>) assessmentData.get(Constants.CHILDREN);
			if (CollectionUtils.isEmpty(children)) return questionMap;
			// Fetch recursive question IDs
			List<String> questionIds = fetchRecursiveQuestionIds(children, new ArrayList<>());
			if (CollectionUtils.isEmpty(questionIds)) return questionMap;
			// Construct the question map
			questionMap = qListFrmAssessService(questionIds);
			if (!CollectionUtils.isEmpty(questionMap))
				redisCacheMgr.putCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTIONS, questionMap,serverProperties.getRedisQuestionsReadTimeOut().intValue());
			return questionMap;
		} else {
			return mapper.readValue(questStr, new TypeReference<Map<String, Object>>() {
			});
		}
	}

	private List<String> fetchRecursiveQuestionIds(List<Map<String, Object>> children, List<String> questionIds) {
		if (CollectionUtils.isEmpty(children))
			return questionIds;
		for (Map<String, Object> child : children) {
			if (Constants.QUESTION_SET.equalsIgnoreCase((String) child.get(Constants.OBJECT_TYPE))) {
				fetchRecursiveQuestionIds((List<Map<String, Object>>) child.get(Constants.CHILDREN), questionIds);
			} else {
				questionIds.add((String) child.get(Constants.IDENTIFIER));
			}
		}
		return questionIds;
	}
	public Map<String, Object> fetchWheebox(String userId) {
		String res = redisCacheMgr.getContentFromCache(serverProperties.getRedisWheeboxKey()+"_"+userId);
		if(!StringUtils.isEmpty(res)) {
            try {
               return  mapper.readValue(res, new TypeReference<Map<String, Object>>() {
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
		return new HashMap<>();
	}


	/**
	 *
	 * @param questionSetDetailsMap a map containing details about the question set.
	 * @param originalQuestionList  a list of original question identifiers.
	 * @param userQuestionList      a list of maps where each map represents a user's question with its details.
	 * @param questionMap           a map containing additional question-related information.
	 * @return a map with validation results and resultMap.
	 */
	public Map<String, Object> validateQumlAssessmentV2(Map<String, Object> questionSetDetailsMap,List<String> originalQuestionList,
													  List<Map<String, Object>> userQuestionList,Map<String,Object> questionMap) {
		try {
			Integer correct = 0;
			Integer blank = 0;
			Integer inCorrect = 0;
            Double sectionMarks =0.0;
			Map<String,Object> questionSetSectionScheme = new HashMap<>();
			String assessmentType= (String)questionSetDetailsMap.get(Constants.ASSESSMENT_TYPE);
			String negativeWeightAgeEnabled;
			int negativeMarksValue = 0;
			int minimumPassPercentage = 0;
			if (questionSetDetailsMap.get(Constants.MINIMUM_PASS_PERCENTAGE) != null) {
				minimumPassPercentage = (int) questionSetDetailsMap.get(Constants.MINIMUM_PASS_PERCENTAGE);
			}
			Integer totalMarks= (Integer) questionSetDetailsMap.get(Constants.TOTAL_MARKS);
			Map<String, Object> resultMap = new HashMap<>();
			Map<String, Object> answers = getQumlAnswers(originalQuestionList,questionMap);
			Map<String, Object> optionWeightages = new HashMap<>();
			if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
				optionWeightages = getOptionWeightages(originalQuestionList, questionMap);
			} else if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
				questionSetSectionScheme = (Map<String, Object>) questionSetDetailsMap.get(Constants.QUESTION_SECTION_SCHEME);
				negativeWeightAgeEnabled = (String) questionSetDetailsMap.get(Constants.NEGATIVE_MARKING_PERCENTAGE);
				negativeMarksValue = Integer.parseInt(negativeWeightAgeEnabled.replace("%", ""));
			}
			for (Map<String, Object> question : userQuestionList) {
				Map<String, Object> proficiencyMap = getProficiencyMap(questionMap, question);
				List<String> marked = new ArrayList<>();
				List<Map<String, Object>> options = new ArrayList<>();
				options = handleqTypeQuestion(question, options, marked, assessmentType, optionWeightages, sectionMarks);
				if (CollectionUtils.isEmpty(marked)){
					blank++;
					question.put(Constants.RESULT,Constants.BLANK);
				}
				else {
					List<String> answer = (List<String>) answers.get(question.get(Constants.IDENTIFIER));
					sortAnswers(answer);
					sortAnswers(marked);
					if(assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
						if (answer.equals(marked)) {
							question.put(Constants.RESULT, Constants.CORRECT);
							correct++;
							sectionMarks = handleCorrectAnswer(sectionMarks, questionSetSectionScheme, proficiencyMap);
						} else {
							question.put(Constants.RESULT, Constants.INCORRECT);
							inCorrect++;
							sectionMarks = handleIncorrectAnswer(negativeMarksValue, sectionMarks, questionSetSectionScheme, proficiencyMap);
						}
					}
					sectionMarks = calculateScoreForOptionWeightage(question, assessmentType, optionWeightages, sectionMarks, marked);
				}
			}
			blank = handleBlankAnswers(userQuestionList, answers, blank);
			updateResultMap(userQuestionList, correct, blank, inCorrect, resultMap, sectionMarks, totalMarks);
			calculatePassPercentage(sectionMarks,totalMarks,correct, blank, inCorrect,assessmentType,resultMap);
			computeSectionResults(sectionMarks, totalMarks, minimumPassPercentage, resultMap);
			return resultMap;
		} catch (Exception ex) {
			logger.error("Error when verifying assessment. Error : ", ex);
		}
		return new HashMap<>();
	}

	private static Double calculateScoreForOptionWeightage(Map<String, Object> question, String assessmentType, Map<String, Object> optionWeightages, Double sectionMarks, List<String> marked) {
		if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
			Map<String, Object> optionWeightageMap = (Map<String, Object>) optionWeightages.get(question.get(Constants.IDENTIFIER));
			for (Map.Entry<String, Object> optionWeightAgeFromOptions : optionWeightageMap.entrySet()) {
				String submittedQuestionSetIndex = marked.get(0);
				if (submittedQuestionSetIndex.equals(optionWeightAgeFromOptions.getKey())) {
					Object value = optionWeightAgeFromOptions.getValue();
					double weightage = 0;
					if (value instanceof Number) {
						weightage = ((Number) value).doubleValue();
					} else if (value instanceof String) {
						weightage = Double.parseDouble((String) value);
					}
					sectionMarks = sectionMarks + weightage;
				}
			}
		}
		return sectionMarks;
	}

	private List<Map<String, Object>> handleqTypeQuestion(Map<String, Object> question, List<Map<String, Object>> options, List<String> marked, String assessmentType, Map<String, Object> optionWeightages, Double sectionMarks) {
		if (question.containsKey(Constants.QUESTION_TYPE)) {
			String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
			Map<String, Object> editorStateObj = (Map<String, Object>) question.get(Constants.EDITOR_STATE);
			options = (List<Map<String, Object>>) editorStateObj
					.get(Constants.OPTIONS);
			getMarkedIndexForEachQuestion(questionType, options, marked, assessmentType);
		}
		return options;
	}


	/**
	 * Retrieves option weightages for a list of questions corresponding to their options.
	 *
	 * @param questions the list of questionIDs/doIds.
	 * @param questionMap the map containing questions/Question Level details.
	 * @return a map containing Identifier mapped to their option and option weightages.
	 * @throws Exception if there is an error processing the questions.
	 */
	private Map<String, Object> getOptionWeightages(List<String> questions, Map<String, Object> questionMap) {
		logger.info("Retrieving option weightages for questions based on the options...");
		Map<String, Object> ret = new HashMap<>();
		for (String questionId : questions) {
			Map<String, Object> optionWeightage = new HashMap<>();
			Map<String, Object> question = (Map<String, Object>) questionMap.get(questionId);
			if (question.containsKey(Constants.QUESTION_TYPE)) {
				String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
				Map<String, Object> editorStateObj = (Map<String, Object>) question.get(Constants.EDITOR_STATE);
				List<Map<String, Object>> options = (List<Map<String, Object>>) editorStateObj.get(Constants.OPTIONS);
				switch (questionType) {
					case Constants.MCQ_SCA:
					case Constants.MCQ_MCA:
					case Constants.MCQ_MCA_W:
						for (Map<String, Object> option : options) {
							Map<String, Object> valueObj = (Map<String, Object>) option.get(Constants.VALUE);
							optionWeightage.put(valueObj.get(Constants.VALUE).toString(), option.get(Constants.ANSWER));
						}
						break;
					default:
						break;
				}
			}
			ret.put(question.get(Constants.IDENTIFIER).toString(), optionWeightage);
		}
		logger.info("Option weightages retrieved successfully.");
		return ret;
	}

	/**
	 * Gets index for each question based on the question type.
	 *
	 *
	 * @param questionType the type of question.
	 * @param options the list of options.
	 * @param marked the list to store marked indices.
	 * @param assessmentType the type of assessment.
	 */
	private void getMarkedIndexForEachQuestion(String questionType, List<Map<String, Object>> options, List<String> marked, String assessmentType) {
		logger.info("Getting marks or index for each question...");
		switch (questionType) {
			case Constants.MTF:
				for (Map<String, Object> option : options) {
					marked.add(option.get(Constants.INDEX).toString() + "-"
							+ option.get(Constants.SELECTED_ANSWER).toString().toLowerCase());
				}
				break;
			case Constants.FTB:
				for (Map<String, Object> option : options) {
					marked.add((String) option.get(Constants.SELECTED_ANSWER));
				}
				break;
			case Constants.MCQ_SCA:
			case Constants.MCQ_MCA:
			case Constants.MCQ_SCA_TF:
				if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
					getMarkedIndexForQuestionWeightAge(options, marked);
				} else if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
					getMarkedIndexForOptionWeightAge(options,marked);
				}
				break;
			case Constants.MCQ_MCA_W:
				if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
					getMarkedIndexForOptionWeightAge(options, marked);
				}
				break;
			default:
				break;
		}
		logger.info("Marks or index retrieved successfully.");
	}

	/**
	 * Gets index for each question based on the question type.
	 *
	 * @param options the list of options.
	 */
	private void getMarkedIndexForOptionWeightAge(List<Map<String, Object>> options, List<String> marked) {
		logger.info("Processing marks for option weightage...");
		for (Map<String, Object> option : options) {
			String submittedQuestionSetIndex = (String) option.get(Constants.INDEX);
			marked.add(submittedQuestionSetIndex);
		}
		logger.info("Marks for option weightage processed successfully.");
	}

	/**
	 * Retrieves the index of marked options from the provided options list if it is a correct answer.
	 *
	 * @param options the list of options.
	 * @param marked  the list to store marked indices for correct answer.
	 */
	private  void getMarkedIndexForQuestionWeightAge(List<Map<String, Object>> options, List<String> marked) {
		for (Map<String, Object> option : options) {
			if ((boolean) option.get(Constants.SELECTED_ANSWER)) {
				marked.add((String) option.get(Constants.INDEX));
			}
		}
	}

	/**
	 * Handles the correct answer scenario by updating the section marks.
	 *
	 * @param sectionMarks the current section marks.
	 * @param questionSetSectionScheme the question set section scheme.
	 * @param proficiencyMap the proficiency map containing question levels.
	 * @return the updated section marks.
	 */
	private Double handleCorrectAnswer(Double sectionMarks, Map<String, Object> questionSetSectionScheme, Map<String, Object> proficiencyMap) {
		logger.info("Handling correct answer scenario...");
		sectionMarks = sectionMarks + (Integer) questionSetSectionScheme.get((String) proficiencyMap.get(Constants.QUESTION_LEVEL));
		logger.info("Correct answer scenario handled successfully.");
		return sectionMarks;
	}

	/**
	 * Handles the incorrect answer scenario by updating the section marks .
	 * and applying negative marking if applicable.
	 *
	 * @param negativeMarksValue the value of negative marks for incorrect answers.
	 * @param sectionMarks the current section marks.
	 * @param questionSetSectionScheme the question set section scheme.
	 * @param proficiencyMap the proficiency map containing question levels.
	 * @return the updated section marks.
	 */
	private Double handleIncorrectAnswer(int negativeMarksValue,Double sectionMarks, Map<String, Object> questionSetSectionScheme, Map<String, Object> proficiencyMap) {
		logger.info("Handling incorrect answer scenario...");
		if (negativeMarksValue > 0) {
			sectionMarks = sectionMarks - (((double)negativeMarksValue /100 ) * (int) questionSetSectionScheme.get((String)proficiencyMap.get(Constants.QUESTION_LEVEL)));
		}
		logger.info("Incorrect answer scenario handled successfully.");
		return sectionMarks;
	}

	/**
	 * Handles blank answers by counting skipped questions.
	 *
	 * @param userQuestionList the list of user questions.
	 * @param answers the map containing answers.
	 * @param blank the current count of blank answers.
	 * @return the updated count of blank answers.
	 */
	private Integer handleBlankAnswers(List<Map<String, Object>> userQuestionList, Map<String, Object> answers, Integer blank) {
		logger.info("Handling blank answers...");
		// Increment the blank counter for skipped question objects
		if (answers.size() > userQuestionList.size()) {
			blank += answers.size() - userQuestionList.size();
		}
		logger.info("Blank answers handled successfully.");
		return blank;
	}

	/**
	 * Updates the result map with assessment data.
	 *
	 * @param userQuestionList the list of user questions.
	 * @param correct the count of correct answers.
	 * @param blank the count of blank answers.
	 * @param inCorrect the count of incorrect answers.
	 * @param resultMap the map to store assessment results.
	 * @param sectionMarks the section marks obtained.
	 * @param totalMarks the total marks for the assessment.
	 */
	private void updateResultMap(List<Map<String, Object>> userQuestionList, Integer correct, Integer blank, Integer inCorrect, Map<String, Object> resultMap, Double sectionMarks, Integer totalMarks) {
		logger.info("Updating result map...");
		resultMap.put(Constants.INCORRECT, inCorrect);
		resultMap.put(Constants.BLANK, blank);
		resultMap.put(Constants.CORRECT, correct);
		resultMap.put(Constants.CHILDREN, userQuestionList);
		resultMap.put(Constants.SECTION_MARKS, sectionMarks);
		resultMap.put(Constants.TOTAL_MARKS, totalMarks);
		logger.info("Result map updated successfully.");
	}


	/**
	 * Computes the result of a section based on section marks, total marks, and minimum pass value.
	 *
	 * @param sectionMarks the marks obtained in the section.
	 * @param totalMarks the total marks available for the section.
	 * @param minimumPassValue the minimum percentage required to pass the section.
	 * @param resultMap the map to store the section result.
	 */
	private  void computeSectionResults(Double sectionMarks, Integer totalMarks, int minimumPassValue, Map<String, Object> resultMap) {
		logger.info("Computing section results...");
		if (sectionMarks > 0 && totalMarks>0 && ((sectionMarks / totalMarks) * 100 >= minimumPassValue)) {
			resultMap.put(Constants.SECTION_RESULT, Constants.PASS);
		} else {
			resultMap.put(Constants.SECTION_RESULT, Constants.FAIL);
		}
		logger.info("Section results computed successfully.");
	}

	private  Map<String, Object> getProficiencyMap(Map<String, Object> questionMap, Map<String, Object> question) {
		return (Map<String, Object>) questionMap.get(question.get(Constants.IDENTIFIER));
	}

	private void sortAnswers(List<String> answer) {
		if (answer.size() > 1)
			Collections.sort(answer);
	}

	/**
	 * Calculates the pass percentage based on the given assessment type and updates the resultMap with the result.
	 *
	 * @param sectionMarks    Marks obtained in the section
	 * @param totalMarks      Total marks possible in the section
	 * @param correct         Number of correct answers
	 * @param blank           Number of blank answers
	 * @param inCorrect       Number of incorrect answers
	 * @param assessmentType  Type of assessment (either "QUESTION_WEIGHTAGE" or "OPTION_WEIGHTAGE")
	 * @param resultMap       Map to store the result of the calculation
	 */
	private static void calculatePassPercentage(Double sectionMarks, Integer totalMarks, Integer correct, Integer blank, Integer inCorrect,String assessmentType,Map<String, Object> resultMap) {
		if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
			resultMap.put(Constants.RESULT, sectionMarks / (double)totalMarks * 100);
		} else if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
			int total;
			total = correct + blank + inCorrect;
			resultMap.put(Constants.RESULT, total == 0 ? 0 : ((correct * 100d) / total));
			resultMap.put(Constants.TOTAL, total);
		}
	}

	/**
	 * Shuffles the list of option maps.
	 *
	 * @param optionsMapList The list of option maps to be shuffled.
	 * @return A new list containing the shuffled option maps.
	 */
	public static List<Map<String, Object>> shuffleOptions(List<Map<String, Object>> optionsMapList) {
		// Create a copy of the original list to avoid modifying the input list
		List<Map<String, Object>> shuffledList = new ArrayList<>(optionsMapList);
		// Shuffle the list using Collections.shuffle()
		Collections.shuffle(shuffledList);
		return shuffledList;
	}

	@Override
	public Map<String, Object> filterQuestionMapDetailV2(Map<String, Object> questionMapResponse,
														 String primaryCategory) {
		List<String> questionParams = serverProperties.getAssessmentQuestionParams();
		Map<String, Object> updatedQuestionMap = new HashMap<>();
		for (String questionParam : questionParams) {
			if (questionMapResponse.containsKey(questionParam)) {
				updatedQuestionMap.put(questionParam, questionMapResponse.get(questionParam));
			}
		}
		if (questionMapResponse.containsKey(Constants.EDITOR_STATE)
				&& primaryCategory.equalsIgnoreCase(Constants.PRACTICE_QUESTION_SET)) {
			Map<String, Object> editorState = (Map<String, Object>) questionMapResponse.get(Constants.EDITOR_STATE);
			updatedQuestionMap.put(Constants.EDITOR_STATE, editorState);
		}
		if (questionMapResponse.containsKey(Constants.CHOICES)
				&& updatedQuestionMap.containsKey(Constants.PRIMARY_CATEGORY)) {
			Map<String, Object> choicesObj = (Map<String, Object>) questionMapResponse.get(Constants.CHOICES);
			Map<String, Object> updatedChoicesMap = new HashMap<>();
			if (choicesObj.containsKey(Constants.OPTIONS)) {
				List<Map<String, Object>> optionsMapList = (List<Map<String, Object>>) choicesObj
						.get(Constants.OPTIONS);
				updatedChoicesMap.put(Constants.OPTIONS, shuffleOptions(optionsMapList));
			}
			updatedQuestionMap.put(Constants.CHOICES, updatedChoicesMap);
		}
		if (questionMapResponse.containsKey(Constants.RHS_CHOICES)
				&& updatedQuestionMap.containsKey(Constants.PRIMARY_CATEGORY) && updatedQuestionMap
				.get(Constants.PRIMARY_CATEGORY).toString().equalsIgnoreCase(Constants.MTF_QUESTION)) {
			List<Object> rhsChoicesObj = (List<Object>) questionMapResponse.get(Constants.RHS_CHOICES);
			Collections.shuffle(rhsChoicesObj);
			updatedQuestionMap.put(Constants.RHS_CHOICES, rhsChoicesObj);
		}

		return updatedQuestionMap;
	}


	/**
	 * @param questionSetDetailsMap a map containing details about the question set.
	 * @param originalQuestionList  a list of original question identifiers.
	 * @param userQuestionList      a list of maps where each map represents a user's question with its details.
	 * @param questionMap           a map containing additional question-related information.
	 * @return a map with validation results and resultMap.
	 */
	public Map<String, Object> validateQumlAssessmentV3(Map<String, Object> questionSetDetailsMap, List<String> originalQuestionList,
														List<Map<String, Object>> userQuestionList, Map<String, Object> questionMap) {
		try {
			String assessmentType = getAssessmentType(questionSetDetailsMap);
			int minimumPassPercentage = getMinimumPassPercentage(questionSetDetailsMap);
			int totalMarks = getTotalMarks(questionSetDetailsMap);

			Map<String, Object> resultMap = new HashMap<>();

			Map<String, Object> answers = getQumlAnswersV2(originalQuestionList, questionMap);

			Integer correct = 0;
			Integer blank = 0;
			Integer inCorrect = 0;
			Double sectionMarks = 0.0;
			Map<String, Object> questionSetSectionScheme = new HashMap<>();
			int negativeMarksValue = 0;
			Map<String, Object> optionWeightages = new HashMap<>();
			if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
				optionWeightages = getOptionWeightages(originalQuestionList, questionMap);
			} else if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
				questionSetSectionScheme = getQuestionSetSectionScheme(questionSetDetailsMap);
				negativeMarksValue = getNegativeMarksValue(questionSetDetailsMap);
			}
			for (Map<String, Object> question : userQuestionList) {
				Map<String, Object> proficiencyMap = getProficiencyMap(questionMap, question);
				List<String> marked = new ArrayList<>();
				handleqTypeQuestionV2(question, marked, assessmentType);
				if (CollectionUtils.isEmpty(marked)) {
					blank++;
					question.put(Constants.RESULT, Constants.BLANK);
				} else {
					List<String> answer = mapper.convertValue(answers.get(question.get(Constants.IDENTIFIER)), new TypeReference<List<String>>() {
					});
					sortAnswers(answer);
					sortAnswers(marked);
					if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
						if (answer.equals(marked)) {
							question.put(Constants.RESULT, Constants.CORRECT);
							correct++;
							sectionMarks = handleCorrectAnswer(sectionMarks, questionSetSectionScheme, proficiencyMap);
						} else {
							question.put(Constants.RESULT, Constants.INCORRECT);
							inCorrect++;
							sectionMarks = handleIncorrectAnswer(negativeMarksValue, sectionMarks, questionSetSectionScheme, proficiencyMap);
						}
					}
					sectionMarks = calculateScoreForOptionWeightage(question, assessmentType, optionWeightages, sectionMarks, marked);
				}
			}
			blank = handleBlankAnswers(userQuestionList, answers, blank);
			updateResultMap(userQuestionList, correct, blank, inCorrect, resultMap, sectionMarks, totalMarks);
			calculatePassPercentage(sectionMarks, totalMarks, correct, blank, inCorrect, assessmentType, resultMap);
			computeSectionResults(sectionMarks, totalMarks, minimumPassPercentage, resultMap);
			return resultMap;
		} catch (Exception ex) {
			logger.error("Error when verifying assessment. Error : ", ex);
		}
		return new HashMap<>();
	}

	/**
	 * Retrieves the negative marks value from the question set details map.
	 *
	 * @param questionSetDetailsMap Map containing question set details
	 * @return Negative marks value as an integer
	 */
	private static int getNegativeMarksValue(Map<String, Object> questionSetDetailsMap) {
		int negativeMarksValue;
		String negativeWeightAgeEnabled;
		negativeWeightAgeEnabled = (String) questionSetDetailsMap.get(Constants.NEGATIVE_MARKING_PERCENTAGE);
		negativeMarksValue = Integer.parseInt(negativeWeightAgeEnabled.replace("%", ""));
		return negativeMarksValue;
	}

	/**
	 * Extracts the question set section scheme from the question set details map.
	 *
	 * @param questionSetDetailsMap Map containing question set details
	 * @return Question set section scheme as a map of string to object
	 */
	private Map<String, Object> getQuestionSetSectionScheme(Map<String, Object> questionSetDetailsMap) {
		return mapper.convertValue(questionSetDetailsMap.get(Constants.QUESTION_SECTION_SCHEME), new TypeReference<Map<String, Object>>() {
		});
	}

	/**
	 * Retrieves the minimum pass percentage from the question set details map.
	 * If the minimum pass percentage is not present in the map, returns 0 as the default value.
	 *
	 * @param questionSetDetailsMap Map containing question set details
	 * @return Minimum pass percentage as an integer
	 */
	private int getMinimumPassPercentage(Map<String, Object> questionSetDetailsMap) {
		if (questionSetDetailsMap.get(Constants.MINIMUM_PASS_PERCENTAGE) != null) {
			return (int) questionSetDetailsMap.get(Constants.MINIMUM_PASS_PERCENTAGE);
		}
		return 0;
	}

	/**
	 * Retrieves the assessment type from the question set details map.
	 *
	 * @param questionSetDetailsMap Map containing question set details
	 * @return Assessment type as a string
	 */
	private String getAssessmentType(Map<String, Object> questionSetDetailsMap) {
		return (String) questionSetDetailsMap.get(Constants.ASSESSMENT_TYPE);
	}

	/**
	 * Retrieves the total marks for the assessment from the question set details map.
	 *
	 * @param questionSetDetailsMap Map containing question set details
	 * @return Total marks as an integer
	 */
	private int getTotalMarks(Map<String, Object> questionSetDetailsMap) {
		Object totalMarksObj = questionSetDetailsMap.get(Constants.TOTAL_MARKS);
		if (totalMarksObj instanceof Number) {
			return ((Number) totalMarksObj).intValue();
		} else {
			return 0;
		}
	}

	private Map<String, Object> getQumlAnswersV2(List<String> questions, Map<String, Object> questionMap) {
		Map<String, Object> ret = new HashMap<>();
		for (String questionId : questions) {
			List<String> correctOption = new ArrayList<>();
			Map<String, Object> question = mapper.convertValue(questionMap.get(questionId), new TypeReference<Map<String, Object>>() {
			});
			if (question.containsKey(Constants.QUESTION_TYPE)) {
				String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
				Map<String, Object> editorStateObj = mapper.convertValue(question.get(Constants.EDITOR_STATE), new TypeReference<Map<String, Object>>() {
				});
				List<Map<String, Object>> options = mapper.convertValue(editorStateObj.get(Constants.OPTIONS), new TypeReference<List<Map<String, Object>>>() {
				});
				switch (questionType) {
					case Constants.MTF:
						for (Map<String, Object> option : options) {
							Map<String, Object> valueObj = mapper.convertValue(option.get(Constants.VALUE), new TypeReference<Map<String, Object>>() {
							});
							correctOption.add(valueObj.get(Constants.VALUE).toString() + "-"
									+ option.get(Constants.ANSWER).toString().toLowerCase());
						}
						break;
					case Constants.FTB:
						processFillInTheBlankOptions(options, correctOption);
						break;
					case Constants.MCQ_SCA:
					case Constants.MCQ_MCA:
					case Constants.MCQ_SCA_TF:
						for (Map<String, Object> option : options) {
							if ((boolean) option.get(Constants.ANSWER)) {
								Map<String, Object> valueObj = mapper.convertValue(option.get(Constants.VALUE), new TypeReference<Map<String, Object>>() {
								});
								correctOption.add(valueObj.get(Constants.VALUE).toString());
							}
						}
						break;
					default:
						break;
				}
			} else {
				List<Map<String, Object>> optionsList = mapper.convertValue(question.get(Constants.OPTIONS), new TypeReference<List<Map<String, Object>>>() {
				});
				for (Map<String, Object> options : optionsList) {
					if ((boolean) options.get(Constants.IS_CORRECT))
						correctOption.add(options.get(Constants.OPTION_ID).toString());
				}
			}
			ret.put(question.get(Constants.IDENTIFIER).toString(), correctOption);
		}

		return ret;
	}


	/**
	 * Handles the logic for processing type question v2.
	 *
	 * @param question       Map containing question details
	 * @param marked         List of marked options
	 * @param assessmentType Assessment type
	 */
	private void handleqTypeQuestionV2(Map<String, Object> question, List<String> marked, String assessmentType) {
		if (question.containsKey(Constants.QUESTION_TYPE)) {
			String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
			Map<String, Object> editorStateObj = mapper.convertValue(question.get(Constants.EDITOR_STATE), new TypeReference<Map<String, Object>>() {
			});
			List<Map<String, Object>> options = mapper.convertValue(editorStateObj.get(Constants.OPTIONS),
					new TypeReference<List<Map<String, Object>>>() {
					});
			getMarkedIndexForEachQuestionV2(questionType, options, marked, assessmentType);
		}
	}


	/**
	 * Retrieves the marked index for each question based on the question type.
	 *
	 * @param questionType   Type of the question
	 * @param options        List of options for the question
	 * @param marked         List to store the marked indexes
	 * @param assessmentType Type of assessment
	 */
	private void getMarkedIndexForEachQuestionV2(String questionType, List<Map<String, Object>> options, List<String> marked, String assessmentType) {
		logger.info("Getting marks or index for each question...");
		switch (questionType) {
			case Constants.MTF:
				for (Map<String, Object> option : options) {
					marked.add(option.get(Constants.INDEX).toString() + "-"
							+ option.get(Constants.SELECTED_ANSWER).toString().toLowerCase());
				}
				break;
			case Constants.FTB:
				processFillInTheBlankUserAnswers(options, marked);
				break;
			case Constants.MCQ_SCA:
			case Constants.MCQ_MCA:
			case Constants.MCQ_SCA_TF:
				if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
					getMarkedIndexForQuestionWeightAge(options, marked);
				} else if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
					getMarkedIndexForOptionWeightAge(options, marked);
				}
				break;
			case Constants.MCQ_MCA_W:
				if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
					getMarkedIndexForOptionWeightAge(options, marked);
				}
				break;
			default:
				break;
		}
		logger.info("Marks or index retrieved successfully.");
	}

	public String validateContextLocking(Map<String, Object> assessmentAllDetail, String parentContextId, SBApiResponse response, String userId, String assessmentIdentifier) {
		String errMsg = "";
		String contextCategory = (String) assessmentAllDetail.get(Constants.CONTEXT_CATEGORY_TAG);
		logger.info("{} AssessmentContextCategory: {}, parentContextId: {}",Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, contextCategory, parentContextId);
		if (Constants.FINAL_PROGRAM_ASSESSMENT.equalsIgnoreCase(contextCategory)) {
			if (StringUtils.isNotBlank(parentContextId)) {
				Map<String, Object> contentDetails = contentService.readContentFromCache(parentContextId, null);
				if (MapUtils.isNotEmpty(contentDetails)) {
					String contextLockingType = (String) contentDetails.get(Constants.CONTEXT_LOCKING_TYPE);
					logger.info("{} {}", Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, contextLockingType);
					if (Constants.COURSE_ASSESSMENT_ONLY.equalsIgnoreCase(contextLockingType)) {
						Set<String> courseIds = contentService.readChildCoursesFromCache(parentContextId);
						logger.info("{} children courseIds: {}", Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, courseIds);
						if (!isAllCourseCompleted(userId, new ArrayList<>(courseIds))) {
							errMsg = Constants.USER_COURSES_NOT_COMPLETED;
							updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
							return errMsg;
						} else {
							logger.info("{} user has completed all the children courses", Constants.PREFIX_VALIDATE_CONTEXT_LOCKING);
						}
					} else {
						errMsg = Constants.UNSUPPORTED_FEATURE;
						updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
						return errMsg;
					}
				} else {
					errMsg = Constants.CONTENT_NOT_FOUND;
					updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
					return errMsg;
				}
			} else {
				errMsg = Constants.INVALID_COURSE_REQUEST;
				updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
				return errMsg;
			}
		} else if (Constants.FINAL_MILESTONE_ASSESSMENT.equalsIgnoreCase(contextCategory)) {
			errMsg = validateContextLockingForLearningPathway(parentContextId, response, userId,assessmentIdentifier);
		}
		return errMsg;
	}

	private boolean isAllCourseCompleted(String userId, List<String> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) {
			return false;
		}
		Map<String, Object> propertyMap = new HashMap<>();
		propertyMap.put(Constants.USER_ID_CONSTANT, userId);
		propertyMap.put(Constants.COURSE_ID, courseIds);
		List<Map<String, Object>> enrolments = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
				Constants.KEYSPACE_SUNBIRD_COURSES, Constants.TABLE_USER_ENROLMENT, propertyMap,
				Arrays.asList(Constants.USER_ID_CONSTANT, Constants.COURSE_ID, Constants.STATUS));
		if (CollectionUtils.isEmpty(enrolments) || enrolments.size() < courseIds.size()) {
			logger.info(
					"{} Failed to fetch enrolment list for userId: {}, courseIds: {}", Constants.PREFIX_VALIDATE_COMPLETED_COURSE,
					userId, courseIds);
			return false;
		}

		for (Map<String, Object> enrolment : enrolments) {
			if (Constants.ASSESSMENT_STATUS_COMPLETED != (int) enrolment.get(Constants.STATUS)) {
				logger.info("{} User: {}, not completed course: {}", Constants.PREFIX_VALIDATE_COMPLETED_COURSE,
						userId, (String) enrolment.get(Constants.COURSE_ID));
				return false;
			}
		}
		return true;
	}

	@Override
	public String readAssessmentRecord(String assessmentIdentifier, List<String> fields) {
		Map<String, String> headers = new HashMap<>();
		try {
			String fieldsStr = StringUtils.join(fields, ",");
			StringBuilder sbUrl = new StringBuilder(serverProperties.getContentHost());
			sbUrl.append(serverProperties.getCourseReadPath())
					.append(assessmentIdentifier)
					.append("?fields=").append(fieldsStr);

			headers.put(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);

			Map<String, Object> response = outboundRequestHandlerService.fetchResultUsingGet(sbUrl.toString(), headers);
			if (MapUtils.isNotEmpty(response)) {
				response = (Map<String, Object>) response.get(Constants.RESULT);
				if (MapUtils.isNotEmpty(response)) {
					Object content = response.get(Constants.CONTENT);
					if (content instanceof Map) {
						Object languageObj = ((Map<?, ?>) content).get(Constants.LANGUAGE);
						if (languageObj instanceof List && !((List<?>) languageObj).isEmpty()) {
							return ((List<?>) languageObj).get(0).toString(); // ✅ Return the first language directly
						}
					}
				} else {
					logger.error("AssessmentUtilServiceV2Impl:readAssessmentLanguage No data found in RESULT");
				}
			} else {
				logger.error("AssessmentUtilServiceV2Impl:readAssessmentLanguage No data found in response");
			}
		} catch (Exception e) {
			logger.error("Error during assessment read for {}: {}", assessmentIdentifier, e.getMessage(), e);
		}
		return "";
	}


	public Instant parseStartTimeToInstant(Object startTimeObj) {
		if (startTimeObj instanceof Long) {
			return Instant.ofEpochMilli((Long) startTimeObj);
		} else if (startTimeObj instanceof String) {
			String startTimeStr = (String) startTimeObj;
			if (startTimeStr.matches("\\d+")) {
				return Instant.ofEpochMilli(Long.parseLong(startTimeStr));
			} else {
				return Instant.parse(startTimeStr);
			}
		} else if (startTimeObj instanceof Instant) {
			return (Instant) startTimeObj;
		} else if (startTimeObj instanceof Date) {
			return ((Date) startTimeObj).toInstant();
		} else {
			throw new IllegalArgumentException("Unsupported start time type: " +
					(startTimeObj != null ? startTimeObj.getClass().getName() : "null"));
		}
	}

	public Long parseStartTimeToLong(Object startTimeObj) {
		if (startTimeObj instanceof Date) {
			return ((Date) startTimeObj).getTime();
		} else if (startTimeObj instanceof Instant) {
			return ((Instant) startTimeObj).toEpochMilli();
		} else if (startTimeObj instanceof Long) {
			return (Long) startTimeObj;
		} else if (startTimeObj instanceof String) {
			String str = (String) startTimeObj;
			if (str.matches("\\d+")) {
				return Long.parseLong(str);
			} else {
				return Instant.parse(str).toEpochMilli(); // ISO 8601 string
			}
		}
        return 0L;
    }

	@Override
	public String readContentRecord(String courseId, List<String> fields) {
		try {
			Map<String, Object> response = contentService.readContentFromCache(courseId, fields);
			if (MapUtils.isNotEmpty(response)) {
				Object languageMapObj = response.get(Constants.LANGUAGE_MAP_V1);
				if (languageMapObj instanceof Map) {
					Map<?, ?> languageMap = (Map<?, ?>) languageMapObj;
					for (Object value : languageMap.values()) {
						if (value instanceof Map) {
							Map<?, ?> langDetails = (Map<?, ?>) value;
							Object isBaseLang = langDetails.get("isBaseLang");
							if (Boolean.TRUE.equals(isBaseLang)) {
								return String.valueOf(langDetails.get("id"));
							}
						}
						return courseId;
					}
				} else {
					logger.error("AssessmentUtilServiceV2Impl:readAssessmentLanguage No data found in RESULT");
				}
			}
		} catch (Exception e) {
			logger.error("Error during assessment read for {}: {}", courseId, e.getMessage(), e);
		}
		return courseId;
	}

    @Override
    public String validateAssessmentLanguageAndNodes(Map<String, Object> submitRequest) {
        logger.info("Validating assessment language and nodes for request: {}", submitRequest);
        try {
            String assessmentLanguageReq = (String) submitRequest.get(Constants.LANGUAGE);
            String assessmentIdFromRequest = (String) submitRequest.get(Constants.IDENTIFIER);

            Map<String, Object> contentRead = contentService.readContent(submitRequest.get(Constants.COURSE_ID).toString());
            if (MapUtils.isNotEmpty(contentRead)) {

                String courseCategory = (String) contentRead.get(Constants.COURSE_CATEGORY);
                List<String> leafNodes = (List<String>) contentRead.get(Constants.LEAF_NODES);

                if (StringUtils.isNotBlank(courseCategory) &&
                        courseCategory.equalsIgnoreCase(Constants.MULTILINGUAL_COURSE)) {
                    return Constants.COURSEID_ERROR + submitRequest.get(Constants.COURSE_ID);
                }

                if (StringUtils.isNotBlank(courseCategory) &&
                        courseCategory.equalsIgnoreCase(Constants.LEARNING_PATHWAY)) {
                    logger.info("Processing Learning Pathway validation for courseId: {}", submitRequest.get(Constants.COURSE_ID));
					return validateLearningPathwayAssessment(contentRead, (List<Map<String, Object>>) contentRead.get(Constants.MILESTONES_V1), assessmentIdFromRequest,
                            submitRequest);
                }
                String baseLanguage = ((List<String>) contentRead.get(Constants.LANGUAGE)).get(0);
                if (StringUtils.isBlank(assessmentLanguageReq)) {
                    submitRequest.put(Constants.LANGUAGE, baseLanguage);
                } else if (!assessmentLanguageReq.equalsIgnoreCase(baseLanguage)) {
                    Map<String, Object> languageMapV1 = (Map<String, Object>) contentRead.get(Constants.LANGUAGE_MAP_V1);
                    Map<String, Object> langData = (Map<String, Object>) languageMapV1.get(assessmentLanguageReq.toLowerCase());

                    if (MapUtils.isEmpty(langData)) {
                        return "Requested language not available: " + assessmentLanguageReq;
                    }

                    String mlCourseId = (String) langData.get(Constants.ID);
                    Map<String, Object> mlCourseContent = contentService.readContent(mlCourseId);
                    List<String> mlLeafNodes = (List<String>) mlCourseContent.get(Constants.LEAF_NODES);

                    if (CollectionUtils.isEmpty(mlLeafNodes) || !mlLeafNodes.contains(assessmentIdFromRequest)) {
                        return "Assessment " + assessmentIdFromRequest +
                                " not found in multilingual course " + mlCourseId;
                    }
                } else {
                    if (CollectionUtils.isEmpty(leafNodes) || !leafNodes.contains(assessmentIdFromRequest)) {
                        return "Assessment " + assessmentIdFromRequest +
                                " not found in base course " + submitRequest.get(Constants.COURSE_ID).toString();

                    }
                }
            }
            return "";
        } catch (Exception e) {
            logger.error("Error during assessment language and nodes validation: {}", e.getMessage(), e);
            return "Error during assessment language and nodes validation: " + e.getMessage();
        }
    }

	/**
	 * Processes Fill in the Blank (FTB) question options to extract correct answers.
	 *
	 * Supports two FTB formats:
	 * 1. Type Input: User types answer (answer="B1", value.body="correct")
	 * 2. Dropdown: User selects from options (answer="B1"/"B2"/"none", distractors have answer="none")
	 *
	 * Processing rules:
	 * - B1/B2/B3 format: Maps to positions 0/1/2 as "position-answer" (e.g., "0-correct")
	 * - Legacy POSITION field: Uses explicit position field if present
	 * - Skips options with answer="none" (distractors)
	 *
	 * @param options the list of FTB options from the question
	 * @param correctOption the list to populate with correct answers in position-text format
	 */
	private void processFillInTheBlankOptions(List<Map<String, Object>> options, List<String> correctOption) {
		options.stream()
				.filter(option -> {
					Object answer = option.get(Constants.ANSWER);
					return (answer instanceof Boolean boolValue && boolValue) || answer instanceof String;
				})
				.forEach(option -> {
					Map<String, Object> valueObj = mapper.convertValue(
							option.get(Constants.VALUE),
							new TypeReference<Map<String, Object>>() {}
					);
					String answerText = valueObj.get(Constants.BODY).toString().trim();
					Object answer = option.get(Constants.ANSWER);
					// Handle new format: answer field contains "B1", "B2", etc. (skip "none")
					if (answer instanceof String answerValue &&
							!answerValue.equalsIgnoreCase("none") &&
							answerValue.toLowerCase().startsWith("b")) {
						String blankPosition = answerValue.substring(1);
						int position = Integer.parseInt(blankPosition) - 1; // B1 is index 0
						String formattedAnswer = position + "-" + answerText;
						correctOption.add(formattedAnswer);
					}
					// Handle legacy format with POSITION field
					else if (MapUtils.isNotEmpty(option) &&
							option.containsKey(Constants.POSITION) &&
							option.get(Constants.POSITION) != null) {
						int position = Integer.parseInt((String) option.get(Constants.POSITION)) - 1;
						String formattedAnswer = position + "-" + answerText;
						correctOption.add(formattedAnswer);
					}
					// For Boolean answer=true (legacy support) - already filtered for true in stream
					else if (answer instanceof Boolean) {
						correctOption.add(answerText);
					}
					// Skip all other cases (including answer="none" distractors)
				});
	}

	/**
	 * Processes user-submitted FTB answers into standardized format.
	 * Supports INDEX-based format ("position-answer") and legacy format (answer only).
	 * Extracted to eliminate code duplication and centralize FTB answer processing logic.
	 *
	 * @param options List of user-selected answer options from submission
	 * @param marked List to populate with formatted user answers for scoring comparison
	 */
	private void processFillInTheBlankUserAnswers(List<Map<String, Object>> options, List<String> marked) {
		if (CollectionUtils.isEmpty(options))
			return;
		options.stream()
				.filter(option -> MapUtils.isNotEmpty(option) && option.containsKey(Constants.SELECTED_ANSWER))
				.forEach(option -> {
					if (!(option.get(Constants.SELECTED_ANSWER) instanceof String selectedAnswer)
							|| StringUtils.isBlank(selectedAnswer)) {
						return;
					}
					// INDEX-based format: "position-answer" (e.g., "0-userAnswer")
					if (option.containsKey(Constants.INDEX) && option.get(Constants.INDEX) != null) {
						marked.add(option.get(Constants.INDEX) + "-" + selectedAnswer.trim());
					}
					// Legacy format: just the answer text
					else {
						marked.add(selectedAnswer.trim());
					}
				});
	}
	/**
	 * Checks if cool-off period is configured and valid for an assessment.
	 *
	 * @param assessmentAllDetail the complete assessment hierarchy containing cool-off configuration
	 * @return true if cool-off period exists and is a valid Integer greater than 0, false otherwise
	 */
	@Override
	public boolean hasCoolOffPeriod(Map<String, Object> assessmentAllDetail) {
		if (!assessmentAllDetail.containsKey(Constants.COOL_OFF_PERIOD)) {
			return false;
		}
		Object coolOffPeriodObj = assessmentAllDetail.get(Constants.COOL_OFF_PERIOD);
		if (coolOffPeriodObj instanceof Integer coolOffPeriodDays) {
			return coolOffPeriodDays > 0;
		}
		return false;
	}

	/**
	 * Validates if the user is within the cool-off period for retaking an assessment.
	 * The cool-off period prevents immediate retakes after exhausting retry attempts.
	 *
	 * Note: This method assumes coolOffPeriod is already validated by the caller (hasCoolOffPeriod).
	 *
	 * @param userId                  the user's unique identifier
	 * @param assessmentIdentifier    the assessment's unique identifier
	 * @param assessmentAllDetail     the complete assessment hierarchy containing cool-off configuration
	 * @param userAssessmentDataList  list of user's previous assessment attempts, ordered by most recent first
	 * @return empty string if validation passes, error message if cool-off period is active
	 */
	@Override
	public String validateCoolOffPeriod(String userId, String assessmentIdentifier,
										 Map<String, Object> assessmentAllDetail,
										 List<Map<String, Object>> userAssessmentDataList) {
		try {
			int coolOffPeriodDays = (Integer) assessmentAllDetail.get(Constants.COOL_OFF_PERIOD);
			if (userAssessmentDataList.isEmpty()) {
				logger.debug("No assessment history - User: {}, Assessment: {}", userId, assessmentIdentifier);
				return Constants.EMPTY;
			}
			Map<String, Object> latestAssessment = userAssessmentDataList.get(0);
			Object endTimeObj = latestAssessment.get(Constants.END_TIME);
			if (endTimeObj == null) {
				logger.warn("No end time in latest assessment - User: {}, Assessment: {}", userId, assessmentIdentifier);
				return Constants.EMPTY;
			}
			Instant latestEndTime = convertToInstant(endTimeObj, userId, assessmentIdentifier);
			if (latestEndTime == null) {
				return Constants.EMPTY;
			}
			Instant coolOffEndTime = latestEndTime.plus(coolOffPeriodDays, ChronoUnit.DAYS);
			Instant currentTime = Instant.now();
			if (currentTime.isBefore(coolOffEndTime)) {
				long remainingDays = ChronoUnit.DAYS.between(currentTime, coolOffEndTime);
				logger.info("Cool-off active - User: {}, Assessment: {}, Remaining: {} days",
						userId, assessmentIdentifier, remainingDays + 1);
				return serverProperties.getAssessmentCoolOffErrorMessage()
						.replace("{remainingDays}", String.valueOf(remainingDays + 1))
						.replace("{coolOffPeriod}", String.valueOf(coolOffPeriodDays));
			}
			logger.info("Cool-off period completed - User: {} can retake assessment: {}", userId, assessmentIdentifier);
		} catch (Exception e) {
			logger.error("Cool-off validation error - User: {}, Assessment: {}, Exception: {}",
					userId, assessmentIdentifier, e.getMessage(), e);
		}
		return Constants.EMPTY;
	}

	/**
	 * Converts an object to Instant, handling multiple input types.
	 *
	 * @param endTimeObj the end time object to convert
	 * @param userId the user's unique identifier
	 * @param assessmentIdentifier the assessment's unique identifier
	 * @return Instant representation of the end time, or null if conversion fails
	 */
	private Instant convertToInstant(Object endTimeObj, String userId, String assessmentIdentifier) {
		if (endTimeObj instanceof Instant instant) {
			return instant;
		}
		if (endTimeObj instanceof Date date) {
			return date.toInstant();
		}
		logger.error("Unexpected end time format: {} - User: {}, Assessment: {}",
				endTimeObj.getClass().getSimpleName(), userId, assessmentIdentifier);
		return null;
	}

	public String validateContextLockingForLearningPathway(String parentContextId, SBApiResponse response, String userId, String assessmentIdentifier) {
		if (StringUtils.isBlank(parentContextId)) {
			updateErrorDetails(response, Constants.INVALID_COURSE_REQUEST, HttpStatus.BAD_REQUEST);
			return Constants.INVALID_COURSE_REQUEST;
		}
		Map<String, Object> contentDetails = contentService.readContent(parentContextId);
		if (MapUtils.isEmpty(contentDetails)) {
			updateErrorDetails(response, Constants.CONTENT_NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
			return Constants.CONTENT_NOT_FOUND;
		}
		List<Map<String, Object>> milestones = (List<Map<String, Object>>) contentDetails.get(Constants.MILESTONES_V1);
		if (CollectionUtils.isEmpty(milestones)) {
			logger.warn("{} No milestones found for pathway: {}", Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, parentContextId);
			updateErrorDetails(response, Constants.CONTENT_NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
			return Constants.CONTENT_NOT_FOUND;
		}
		MilestoneInfo targetMilestoneInfo = findMilestoneByAssessmentWithIndex(milestones, assessmentIdentifier);
		if (targetMilestoneInfo == null) {
			logger.warn("{} Assessment {} not found in milestones", Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, assessmentIdentifier);
			String errMsg = "Assessment not found in learning pathway milestones";
			updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
			return errMsg;
		}
		String previousMilestoneError = validatePreviousMilestoneCompletion(milestones, targetMilestoneInfo.index, userId, response);
		if (StringUtils.isNotBlank(previousMilestoneError)) {
			return previousMilestoneError;
		}
		Set<String> mandatoryCourseIds = extractMandatoryCourseIds(targetMilestoneInfo.milestone);
		return validateMandatoryCourseCompletion(mandatoryCourseIds, userId, response);
	}

	/**
	 * Inner class to hold milestone information with its index.
	 */
	private static class MilestoneInfo {
		Map<String, Object> milestone;
		int index;
		MilestoneInfo(Map<String, Object> milestone, int index) {
			this.milestone = milestone;
			this.index = index;
		}
	}

	/**
	 * Validates that the previous milestone's assessment has been completed.
	 * For the first milestone (index 0), no validation is needed.
	 *
	 * @param milestones the list of all milestones
	 * @param currentMilestoneIndex the index of the current milestone
	 * @param userId the user's unique identifier
	 * @param response the API response object
	 * @return empty string if validation passes, error message otherwise
	 */
	private String validatePreviousMilestoneCompletion(List<Map<String, Object>> milestones, int currentMilestoneIndex,
			String userId, SBApiResponse response) {
		if (currentMilestoneIndex == 0) {
			return Constants.EMPTY;
		}
		Map<String, Object> previousMilestone = milestones.get(currentMilestoneIndex - 1);
		String previousAssessmentId = extractAssessmentIdentifier(previousMilestone);
		if (StringUtils.isBlank(previousAssessmentId)) {
			logger.debug("{} Previous milestone (index {}) has no assessment identifier",
				Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, currentMilestoneIndex - 1);
			return Constants.EMPTY;
		}
		if (!isAssessmentPassed(userId, previousAssessmentId)) {
			String errMsg = "Previous milestone assessment must be completed before attempting this assessment";
			logger.warn("{} User {} failed validation - previous assessment {} not completed",
				Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId, previousAssessmentId);
			updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
			return errMsg;
		}
		return Constants.EMPTY;
	}

	/**
	 * Extracts the assessment identifier from a milestone.
	 *
	 * @param milestone the milestone map
	 * @return the assessment identifier, or null if not found
	 */
	private String extractAssessmentIdentifier(Map<String, Object> milestone) {
		Map<String, Object> assessmentDetail = (Map<String, Object>) milestone.get(Constants.ASSESSMENT_DETAIL);
		if (MapUtils.isEmpty(assessmentDetail)) {
			return null;
		}
		return (String) assessmentDetail.get(Constants.IDENTIFIER);
	}

	/**
	 * Finds the milestone that contains the specified assessment and returns it with its index.
	 *
	 * @param milestones the list of milestones to search
	 * @param assessmentIdentifier the assessment identifier to match
	 * @return MilestoneInfo containing the milestone and its index, or null if not found
	 */
	private MilestoneInfo findMilestoneByAssessmentWithIndex(List<Map<String, Object>> milestones, String assessmentIdentifier) {
		return IntStream.range(0, milestones.size())
			.filter(i -> assessmentIdentifier.equals(extractAssessmentIdentifier(milestones.get(i))))
			.mapToObj(i -> new MilestoneInfo(milestones.get(i), i))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Checks if user has completed and passed the specified assessment.
	 * Verifies completion status in user_enrolments_v2 table by checking:
	 * 1. Enrolment is active
	 * 2. Assessment status is 2 (completed) in lang_contentstatus for the recentlanguage
	 *
	 * @param userId the user's unique identifier
	 * @param assessmentId the assessment identifier to check
	 * @return true if assessment is passed/completed, false otherwise
	 */
	private boolean isAssessmentPassed(String userId, String assessmentId) {
		List<Map<String, Object>> enrolmentRecords = fetchUserEnrolmentRecords(userId);
		if (CollectionUtils.isEmpty(enrolmentRecords)) {
			logger.debug("{} No enrolment records found - userId: {}",
				Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId);
			return false;
		}
		return enrolmentRecords.stream()
			.anyMatch(enrolmentRecord -> isAssessmentCompletedInEnrolment(enrolmentRecord, assessmentId, userId));
	}

	/**
	 * Fetches user enrolment records from user_enrolments_v2 table.
	 *
	 * @param userId the user's unique identifier
	 * @return list of enrolment records
	 */
	private List<Map<String, Object>> fetchUserEnrolmentRecords(String userId) {
		Map<String, Object> propertyMap = new HashMap<>();
		propertyMap.put(Constants.USER_ID_CONSTANT, userId);
		List<String> fields = Arrays.asList(
			Constants.USER_ID_CONSTANT,
			Constants.COURSE_ID,
			Constants.BATCH_ID,
			Constants.LANG_CONTENT_STATUS_KEY,
			Constants.ACTIVE,
			Constants.RECENT_LANGUAGE_KEY
		);
		return cassandraOperation.getRecordsByPropertiesWithoutFiltering(
				Constants.KEYSPACE_SUNBIRD_COURSES,
				Constants.TABLE_USER_ENROLMENT,
				propertyMap,
				fields
			);
	}

	/**
	 * Checks if the assessment is completed (status = 2) in the enrolment record.
	 * Validates: active = true, recentlanguage exists, and status = 2 for that language.
	 */
	private boolean isAssessmentCompletedInEnrolment(Map<String, Object> enrolmentRecord, String assessmentId, String userId) {
		if (!isEnrolmentActive(enrolmentRecord, userId, assessmentId)) {
			return false;
		}
		String recentLanguage = getRecentLanguage(enrolmentRecord, userId, assessmentId);
		if (recentLanguage == null) {
			return false;
		}
		return isAssessmentStatusCompleted(enrolmentRecord, assessmentId, userId, recentLanguage);
	}

	/**
	 * Validates if the enrolment is active (handles Boolean or String type).
	 */
	private boolean isEnrolmentActive(Map<String, Object> enrolmentRecord, String userId, String assessmentId) {
		Object activeObj = enrolmentRecord.get(Constants.ACTIVE);
		if (activeObj == null) {
			logger.debug("{} No active field - userId: {}, assessmentId: {}",
				Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId, assessmentId);
			return false;
		}
		boolean isActive = activeObj instanceof Boolean booleanValue
			? booleanValue
			: Boolean.parseBoolean(activeObj.toString());

		if (!isActive) {
			logger.debug("{} Enrolment not active - userId: {}, assessmentId: {}",
				Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId, assessmentId);
		}
		return isActive;
	}

	/**
	 * Extracts recent language from the enrolment record.
	 */
	private String getRecentLanguage(Map<String, Object> enrolmentRecord, String userId, String assessmentId) {
		Object recentLanguageObj = enrolmentRecord.get(Constants.RECENT_LANGUAGE);
		if (recentLanguageObj == null) {
			logger.debug("{} No {} - userId: {}, assessmentId: {}",
				Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, Constants.RECENT_LANGUAGE, userId, assessmentId);
			return null;
		}
		return recentLanguageObj.toString();
	}

	/**
	 * Verifies if assessment status is completed (= 2) for the specified language.
	 */
	private boolean isAssessmentStatusCompleted(Map<String, Object> enrolmentRecord, String assessmentId,
			String userId, String recentLanguage) {
		Object langContentStatusObj = enrolmentRecord.get(Constants.LANG_CONTENT_STATUS);
		if (langContentStatusObj == null) {
			return false;
		}

		try {
			Map<String, Map<String, Integer>> langContentStatus =
				(Map<String, Map<String, Integer>>) langContentStatusObj;
			Map<String, Integer> contentStatusMap = langContentStatus.get(recentLanguage);

			if (contentStatusMap == null) {
				logger.debug("{} Language '{}' not found in lang_contentstatus - userId: {}, assessmentId: {}",
					Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, recentLanguage, userId, assessmentId);
				return false;
			}

			Integer status = contentStatusMap.get(assessmentId);
			boolean isCompleted = status != null && status.equals(Constants.CONTENT_STATUS_COMPLETED);

			if (isCompleted) {
				logger.debug("{} Assessment completed - userId: {}, assessmentId: {}, language: {}, status: {}",
					Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId, assessmentId, recentLanguage, status);
			}
			return isCompleted;
		} catch (Exception e) {
			logger.warn("{} Error checking lang_contentstatus - userId: {}, assessmentId: {}, error: {}",
					Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId, assessmentId, e.getMessage());
			return false;
		}
	}

	/**
	 * Extracts mandatory course IDs from a milestone.
	 *
	 * @param milestone the milestone containing courses
	 * @return set of mandatory course IDs
	 */
	private Set<String> extractMandatoryCourseIds(Map<String, Object> milestone) {
		List<Map<String, Object>> courses = (List<Map<String, Object>>) milestone.get(Constants.COURSES_KEY);
		if (CollectionUtils.isEmpty(courses)) {
			return Collections.emptySet();
		}
		return courses.stream()
			.filter(this::isMandatoryCourse)
			.map(course -> (String) course.get(Constants.IDENTIFIER))
			.filter(StringUtils::isNotBlank)
			.collect(Collectors.toSet());
	}

	/**
	 * Checks if a course is mandatory.
	 *
	 * @param course the course map
	 * @return true if the course is mandatory, false otherwise
	 */
	private boolean isMandatoryCourse(Map<String, Object> course) {
		Object isMandatoryObj = course.get(Constants.IS_MANDATORY);
		if (isMandatoryObj instanceof Boolean booleanValue) {
			return booleanValue;
		}
		if (isMandatoryObj instanceof String stringValue) {
			return Boolean.parseBoolean(stringValue);
		}
		return false;
	}

	/**
	 * Validates if user has completed all mandatory courses.
	 *
	 * @param mandatoryCourseIds set of mandatory course IDs
	 * @param userId the user's unique identifier
	 * @param response the API response object
	 * @return empty string if validation passes, error message otherwise
	 */
	private String validateMandatoryCourseCompletion(Set<String> mandatoryCourseIds, String userId, SBApiResponse response) {
		if (mandatoryCourseIds.isEmpty()) {
			return Constants.EMPTY;
		}
		if (!isAllCourseCompletedV2(userId, new ArrayList<>(mandatoryCourseIds))) {
			String errMsg = Constants.USER_COURSES_NOT_COMPLETED;
			logger.warn("{} User {} has not completed mandatory courses", Constants.PREFIX_VALIDATE_CONTEXT_LOCKING, userId);
			updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
			return errMsg;
		}
		return Constants.EMPTY;
	}

	/**
	 * Validates if all courses in the list are completed by the user (V2 with active check).
	 * Checks both completion status and active status.
	 *
	 * @param userId the user's unique identifier
	 * @param courseIds list of course identifiers to validate
	 * @return true if all courses are completed and active, false otherwise
	 */
	private boolean isAllCourseCompletedV2(String userId, List<String> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) {
			return false;
		}

		List<Map<String, Object>> enrolments = fetchCourseEnrolments(userId, courseIds);
		if (CollectionUtils.isEmpty(enrolments) || enrolments.size() < courseIds.size()) {
			if (logger.isDebugEnabled()) {
				logger.debug("{} Failed to fetch enrolment list - userId: {}, courseIds: {}",
						Constants.PREFIX_VALIDATE_COMPLETED_COURSE, userId, courseIds);
			}
			return false;
		}

		return enrolments.stream()
				.allMatch(enrolment -> isCourseEnrolmentActiveAndCompleted(enrolment, userId));
	}

	/**
	 * Fetches course enrolments with active and status fields.
	 */
	private List<Map<String, Object>> fetchCourseEnrolments(String userId, List<String> courseIds) {
		Map<String, Object> propertyMap = new HashMap<>();
		propertyMap.put(Constants.USER_ID_CONSTANT, userId);
		propertyMap.put(Constants.COURSE_ID, courseIds);
		return cassandraOperation.getRecordsByPropertiesWithoutFiltering(
				Constants.KEYSPACE_SUNBIRD_COURSES,
				Constants.TABLE_USER_ENROLMENT,
				propertyMap,
				Arrays.asList(Constants.USER_ID_CONSTANT, Constants.COURSE_ID, Constants.STATUS, Constants.ACTIVE));
	}

	/**
	 * Validates if a course enrolment is both active and completed.
	 */
	private boolean isCourseEnrolmentActiveAndCompleted(Map<String, Object> enrolment, String userId) {
		String courseId = (String) enrolment.get(Constants.COURSE_ID);
		Object activeObj = enrolment.get(Constants.ACTIVE);
		if (activeObj != null) {
			boolean isActive = activeObj instanceof Boolean booleanValue
					? booleanValue
					: Boolean.parseBoolean(activeObj.toString());
			if (!isActive) {
				return false;
			}
		}
		if (Constants.ASSESSMENT_STATUS_COMPLETED != (int) enrolment.get(Constants.STATUS)) {
			logger.debug("Course not completed : {} {}", userId, courseId);
			return false;
		}
		return true;
	}

	/**
	 * Validates assessment for Learning Pathway by checking preliminary assessment and milestones.
	 *
	 * @param contentRead the content details containing preliminary assessment info
	 * @param milestonesV1 the list of milestones from the Learning Pathway
	 * @param assessmentIdFromRequest the assessment identifier to validate
	 * @param submitRequest the original submit request containing courseId
	 * @return empty string if validation passes, error message otherwise
	 */
	private String validateLearningPathwayAssessment(Map<String, Object> contentRead,
													 List<Map<String, Object>> milestonesV1,
													 String assessmentIdFromRequest,
													 Map<String, Object> submitRequest) {
		String courseId = submitRequest.get(Constants.COURSE_ID).toString();
		// Unified validation: Check if assessment ID matches preliminary assessment
		String preliminaryAssessmentIdFromContent = (String) contentRead.get(Constants.PRELIMINARY_ASSESSMENT);
		if (StringUtils.isNotEmpty(preliminaryAssessmentIdFromContent) &&
			assessmentIdFromRequest.equalsIgnoreCase(preliminaryAssessmentIdFromContent)) {
			logger.info("Assessment {} validated as preliminary assessment for courseId: {}", assessmentIdFromRequest, courseId);
			return Constants.EMPTY;
		}
		// Check if assessment exists in milestones
		if (!CollectionUtils.isEmpty(milestonesV1)) {
			boolean assessmentFound = milestonesV1.stream()
					.map(milestone -> (Map<String, Object>) milestone.get(Constants.ASSESSMENT_DETAIL))
					.filter(MapUtils::isNotEmpty)
					.map(assessmentDetail -> (String) assessmentDetail.get(Constants.IDENTIFIER))
					.anyMatch(assessmentIdFromRequest::equals);

			if (assessmentFound) {
				logger.info("Assessment {} validated successfully in Learning Pathway milestones for courseId: {}", assessmentIdFromRequest, courseId);
				return Constants.EMPTY;
			}
		}
		// Assessment not found in either preliminary or milestones
		logger.warn("Assessment {} not found in Learning Pathway (preliminary or milestones) for courseId: {}", assessmentIdFromRequest, courseId);
		return serverProperties.getAssessmentLearningPathwayAssessmentNotFoundError()
				.replace(Constants.ASSESSMENT_ID_REPLACER, assessmentIdFromRequest)
				.replace(Constants.COURSE_ID_REPLACER, courseId);
	}



	/**
	 * Calculates the number of attempts made in the current cycle for cyclical cooloff assessments.
	 * Iterates through attempts from newest to oldest, stops when a cooloff gap is detected.
	 * Returns count starting from 1 for the first attempt in a new cycle.
	 *
	 * @param userId                  the user's unique identifier
	 * @param assessmentIdentifier    the assessment's unique identifier
	 * @param assessmentAllDetail     the complete assessment hierarchy containing cool-off configuration
	 * @param userAssessmentDataList  list of user's previous assessment attempts, ordered by most recent first
	 * @return number of attempts in current cycle (starts from 1, returns 0 if no submitted attempts)
	 */
	@Override
	public int calculateCyclicalRetakeAttempts(String userId, String assessmentIdentifier,
												Map<String, Object> assessmentAllDetail,
												List<Map<String, Object>> userAssessmentDataList) {
		try {
			if (CollectionUtils.isEmpty(userAssessmentDataList)) {
				return 0;
			}
			int coolOffPeriodDays = (Integer) assessmentAllDetail.get(Constants.COOL_OFF_PERIOD);
			int retakeAttemptsAllowed = assessmentAllDetail.containsKey(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS)
					? (Integer) assessmentAllDetail.get(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS)
					: 0;
			List<Map<String, Object>> submittedAttempts = filterSubmittedAttempts(userAssessmentDataList);
			if (CollectionUtils.isEmpty(submittedAttempts)) {
				return 0;
			}
			int currentCycleCount = countCurrentCycleAttempts(submittedAttempts, coolOffPeriodDays, retakeAttemptsAllowed, userId, assessmentIdentifier);
			logger.info("Current cycle attempts: {} for User: {}, Assessment: {}",
					currentCycleCount, userId, assessmentIdentifier);
			return currentCycleCount;
		} catch (Exception e) {
			logger.error("Error calculating cyclical attempts - User: {}, Assessment: {}, Exception: {}",
					userId, assessmentIdentifier, e.getMessage(), e);
			return 0;
		}
	}

	/**
	 * Filters assessment attempts to include only submitted assessments.
	 *
	 * @param userAssessmentDataList list of all user assessment attempts
	 * @return list containing only submitted attempts
	 */
	private List<Map<String, Object>> filterSubmittedAttempts(List<Map<String, Object>> userAssessmentDataList) {
		return userAssessmentDataList.stream()
				.filter(attempt -> attempt.containsKey(Constants.SUBMIT_ASSESSMENT_RESPONSE_KEY)
						&& attempt.get(Constants.SUBMIT_ASSESSMENT_RESPONSE_KEY) != null)
				.toList();
	}

	/**
	 * Counts attempts in the current cycle by detecting cooloff boundaries.
	 * A new cycle starts when: (1) user exhausted attempts in previous cycle AND (2) waited cooloff period.
	 * Iterates from newest to oldest, stops when encountering a cooloff gap with exhausted attempts after it.
	 * Optimized to stop early once we have enough information.
	 *
	 * @param submittedAttempts list of submitted attempts ordered newest first
	 * @param coolOffPeriodDays the cooloff period in days
	 * @param retakeAttemptsAllowed maximum number of attempts allowed before cooloff period
	 * @param userId the user's unique identifier
	 * @param assessmentIdentifier the assessment's unique identifier
	 * @return number of attempts in the current cycle
	 */
	private int countCurrentCycleAttempts(List<Map<String, Object>> submittedAttempts, int coolOffPeriodDays,
										  int retakeAttemptsAllowed, String userId, String assessmentIdentifier) {
		int currentCycleCount = 1; // Always count the newest attempt
		// Optimization: limit iteration to minimum needed
		// Current cycle has at most retakeAttemptsAllowed attempts
		// We need +1 to check for cycle boundary gap after the current cycle
		int maxIterations = Math.min(submittedAttempts.size(), retakeAttemptsAllowed + 1);
		for (int i = 1; i < maxIterations; i++) {
			// Check if there's a cooloff gap between this attempt and the previous (newer) one
			Instant newerEndTime = convertToInstant(
					submittedAttempts.get(i - 1).get(Constants.END_TIME), userId, assessmentIdentifier);
			Instant currentEndTime = convertToInstant(
					submittedAttempts.get(i).get(Constants.END_TIME), userId, assessmentIdentifier);
			if (newerEndTime != null && currentEndTime != null) {
				long daysBetween = ChronoUnit.DAYS.between(currentEndTime, newerEndTime);
				// If there's a cooloff gap, check if older attempts form a complete exhausted cycle
				if (daysBetween >= coolOffPeriodDays) {
					int attemptsAfterGap = submittedAttempts.size() - i;
					if (attemptsAfterGap >= retakeAttemptsAllowed) {
						// Cycle boundary found - older attempts were exhausted and cooloff was waited
						logger.debug("Cycle boundary at index {} - {} attempts after cooloff gap", i, attemptsAfterGap);
						break;
					}
				}
			}
			currentCycleCount++;
		}
		return currentCycleCount;
	}
}