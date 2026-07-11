package com.assignment.service.impl;

import com.assignment.dto.request.StudentSubmitRequest;
import com.assignment.dto.request.SubmissionReviewRequest;
import com.assignment.dto.response.AssignmentStatusResponse;
import com.assignment.dto.response.StudentResponse;
import com.assignment.dto.response.SubmissionResponse;
import com.assignment.entity.Assignment;
import com.assignment.entity.Student;
import com.assignment.entity.Submission;
import com.assignment.entity.Teacher;
import com.assignment.enums.SubmissionStatus;
import com.assignment.exception.BadRequestException;
import com.assignment.exception.ResourceNotFoundException;
import com.assignment.mapper.SubmissionMapper;
import com.assignment.mapper.UserMapper;
import com.assignment.repository.AssignmentRepository;
import com.assignment.repository.StudentRepository;
import com.assignment.repository.SubmissionRepository;
import com.assignment.repository.TeacherRepository;
import com.assignment.service.CloudinaryService;
import com.assignment.service.RedisService;
import com.assignment.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SubmissionServiceImpl implements SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final AssignmentRepository assignmentRepository;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final CloudinaryService cloudinaryService;
    private final RedisService redisService;
    private final SubmissionMapper submissionMapper;
    private final UserMapper userMapper;
    private final com.assignment.repository.QuestionRepository questionRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.assignment.service.CertificateService certificateService;

    private Student getStudent(String email) {
        Student student = studentRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Student profile not found"));
        if (student.getRole() != com.assignment.enums.Role.STUDENT) {
            throw new com.assignment.exception.UnauthorizedException("Access Denied: Only students can perform this action");
        }
        return student;
    }

    private Teacher getTeacher(String email) {
        Teacher teacher = teacherRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Teacher profile not found"));
        if (teacher.getRole() != com.assignment.enums.Role.TEACHER) {
            throw new com.assignment.exception.UnauthorizedException("Access Denied: Only teachers can perform this action");
        }
        return teacher;
    }

    private AssignmentStatusResponse rebuildAssignmentStatusCache(Long assignmentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
        if (assignment == null) return null;

        if (assignment.getBatch() == null) {
            AssignmentStatusResponse cache = AssignmentStatusResponse.builder()
                    .submittedStudentIds(List.of())
                    .pendingStudentIds(List.of())
                    .submittedCount(0)
                    .pendingCount(0)
                    .completionPercentage(0.0)
                    .build();
            redisService.saveAssignmentStatus(assignmentId, cache);
            return cache;
        }

        List<Student> students = studentRepository.findByBatchId(assignment.getBatch().getId());
        List<Long> allStudentIds = students.stream().map(Student::getId).toList();

        List<Submission> submissions = submissionRepository.findByAssignmentId(assignmentId);
        List<Long> submittedStudentIds = submissions.stream()
                .filter(sub -> sub.getStatus() == SubmissionStatus.SUBMITTED || sub.getStatus() == SubmissionStatus.REVIEWED)
                .map(sub -> sub.getStudent().getId())
                .toList();

        List<Long> pendingStudentIds = allStudentIds.stream()
                .filter(id -> !submittedStudentIds.contains(id))
                .toList();

        int total = allStudentIds.size();
        int submitted = submittedStudentIds.size();
        int pending = pendingStudentIds.size();
        double pct = total > 0 ? ((double) submitted / total) * 100.0 : 0.0;

        AssignmentStatusResponse cache = AssignmentStatusResponse.builder()
                .submittedStudentIds(submittedStudentIds)
                .pendingStudentIds(pendingStudentIds)
                .submittedCount(submitted)
                .pendingCount(pending)
                .completionPercentage(Math.round(pct * 100.0) / 100.0)
                .build();

        redisService.saveAssignmentStatus(assignmentId, cache);
        return cache;
    }

    private boolean checkAnswerCorrectness(com.assignment.entity.Question q, String studentAnswer) {
        if (q == null || studentAnswer == null) {
            return false;
        }
        
        String correctAnswer = q.getCorrectAnswer();
        if (correctAnswer == null) {
            return false;
        }
        
        String cleanCorrect = correctAnswer.trim().toUpperCase();
        String cleanStudent = studentAnswer.trim().toUpperCase();
        
        if (cleanCorrect.equals(cleanStudent)) {
            return true;
        }
        
        // Handle Multiple Select Questions (MSQ) - comma separated
        if ("MSQ".equalsIgnoreCase(q.getQuestionType()) || cleanCorrect.contains(",") || cleanStudent.contains(",")) {
            String[] correctParts = cleanCorrect.split("\\s*,\\s*");
            String[] studentParts = cleanStudent.split("\\s*,\\s*");
            
            if (correctParts.length != studentParts.length) {
                return false;
            }
            
            java.util.Set<String> correctSet = new java.util.HashSet<>(java.util.Arrays.asList(correctParts));
            java.util.Set<String> studentSet = new java.util.HashSet<>(java.util.Arrays.asList(studentParts));
            
            return correctSet.equals(studentSet);
        }
        
        return false;
    }

    @Override
    @Transactional
    public SubmissionResponse submitAssignment(Long assignmentId, StudentSubmitRequest request, String studentEmail) {
        Student student = getStudent(studentEmail);
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));

        if (assignment.getBatch() == null) {
            throw new BadRequestException("This assignment is not assigned to a batch");
        }

        if (student.getBatch() == null) {
            throw new BadRequestException("You are not assigned to a batch");
        }

        if (!student.getBatch().getId().equals(assignment.getBatch().getId())) {
            throw new BadRequestException("This assignment is not assigned to your batch");
        }

        // Validate deadline
        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        if (today.isAfter(assignment.getDueDate()) || 
           (today.isEqual(assignment.getDueDate()) && nowTime.isAfter(assignment.getDueTime()))) {
            if (!assignment.getLateSubmissionAllowed()) {
                throw new BadRequestException("Late submissions are not allowed for this assignment");
            }
        }

        // If it's a QUIZ assignment, evaluate it immediately
        if (assignment.getAssignmentType() == com.assignment.enums.AssignmentType.QUIZ) {
            if (request.getQuizAnswersJson() == null || request.getQuizAnswersJson().isBlank()) {
                throw new BadRequestException("Quiz answers are required");
            }
            
            // Prevent duplicate submissions unless retries are explicitly allowed
            Optional<Submission> existingSub = submissionRepository.findByAssignmentIdAndStudentId(assignmentId, student.getId());
            if (existingSub.isPresent()) {
                int attemptsAllowed = 1;
                try {
                    String instructions = assignment.getInstructions();
                    if (instructions != null && instructions.trim().startsWith("{")) {
                        com.fasterxml.jackson.databind.JsonNode meta = objectMapper.readTree(instructions);
                        if (meta.has("attemptsAllowed")) {
                            attemptsAllowed = meta.get("attemptsAllowed").asInt();
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
                if (attemptsAllowed <= 1) {
                    throw new BadRequestException("You have already completed this quiz");
                }
            }
            
            double totalScore = 0.0;
            try {
                // Parse student answers
                List<java.util.Map<String, Object>> studentAnswers = objectMapper.readValue(
                        request.getQuizAnswersJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {}
                );
                
                // Fetch all questions
                List<com.assignment.entity.Question> questions = questionRepository.findByAssignmentId(assignmentId);
                java.util.Map<Long, com.assignment.entity.Question> questionMap = new java.util.HashMap<>();
                for (com.assignment.entity.Question q : questions) {
                    questionMap.put(q.getId(), q);
                }
                
                for (java.util.Map<String, Object> ans : studentAnswers) {
                    Number qIdNum = (Number) ans.get("questionId");
                    if (qIdNum == null) continue;
                    Long qId = qIdNum.longValue();
                    String selectedOption = (String) ans.get("selectedOption");
                    if (selectedOption == null) selectedOption = "";
                    
                    com.assignment.entity.Question q = questionMap.get(qId);
                    if (q != null && checkAnswerCorrectness(q, selectedOption)) {
                        totalScore += q.getMarks();
                    }
                }
            } catch (Exception e) {
                throw new BadRequestException("Failed to evaluate quiz submission: " + e.getMessage());
            }

            Submission submission;
            if (existingSub.isPresent()) {
                submission = existingSub.get();
                submission.setSubmissionUrl("QUIZ_SUBMISSION");
                submission.setQuizAnswers(request.getQuizAnswersJson());
                submission.setComment(request.getComment());
                submission.setSubmittedAt(LocalDateTime.now());
                submission.setReviewedAt(LocalDateTime.now());
                submission.setMarks(totalScore);
                submission.setFeedback("Auto-graded Quiz");
                submission.setStatus(SubmissionStatus.REVIEWED);
            } else {
                submission = Submission.builder()
                        .assignment(assignment)
                        .student(student)
                        .submissionUrl("QUIZ_SUBMISSION")
                        .quizAnswers(request.getQuizAnswersJson())
                        .comment(request.getComment())
                        .submittedAt(LocalDateTime.now())
                        .reviewedAt(LocalDateTime.now())
                        .marks(totalScore)
                        .feedback("Auto-graded Quiz")
                        .status(SubmissionStatus.REVIEWED)
                        .build();
            }
            
            Submission savedSubmission = submissionRepository.save(submission);
            rebuildAssignmentStatusCache(assignmentId);
            try {
                certificateService.generateCertificateForSubmission(savedSubmission.getId());
            } catch (Exception e) {
                System.err.println("Failed to generate quiz certificate: " + e.getMessage());
            }
            return submissionMapper.toResponse(savedSubmission);
        }

        // File upload
        String fileUrl = null;
        if (request.getFile() != null && !request.getFile().isEmpty()) {
            if (request.getFile().getSize() > assignment.getMaxFileSize()) {
                throw new BadRequestException("File size exceeds maximum allowed size of " + (assignment.getMaxFileSize() / (1024 * 1024)) + " MB");
            }
            fileUrl = cloudinaryService.uploadFile(request.getFile(), "assignment_system/submissions");
        } else {
            throw new BadRequestException("Submission file is required");
        }

        // Check if student already has a submission (allow resubmission / update)
        Optional<Submission> existingSub = submissionRepository.findByAssignmentIdAndStudentId(assignmentId, student.getId());
        Submission submission;
        if (existingSub.isPresent()) {
            submission = existingSub.get();
            submission.setSubmissionUrl(fileUrl);
            submission.setComment(request.getComment());
            submission.setSubmittedAt(LocalDateTime.now());
            submission.setStatus(SubmissionStatus.SUBMITTED);
        } else {
            submission = Submission.builder()
                    .assignment(assignment)
                    .student(student)
                    .submissionUrl(fileUrl)
                    .comment(request.getComment())
                    .submittedAt(LocalDateTime.now())
                    .status(SubmissionStatus.SUBMITTED)
                    .build();
        }

        Submission savedSubmission = submissionRepository.save(submission);

        // Update Redis
        rebuildAssignmentStatusCache(assignmentId);

        try {
            certificateService.generateCertificateForSubmission(savedSubmission.getId());
        } catch (Exception e) {
            System.err.println("Failed to generate assignment certificate on submission: " + e.getMessage());
        }

        return submissionMapper.toResponse(savedSubmission);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubmissionResponse> getSubmittedSubmissions(Long assignmentId, String teacherEmail) {
        Teacher teacher = getTeacher(teacherEmail);
        Assignment assignment = assignmentRepository.findByIdAndTeacherId(assignmentId, teacher.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found or unauthorized"));

        List<Submission> submissions = submissionRepository.findByAssignmentId(assignmentId);
        return submissionMapper.toResponseList(submissions);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudentResponse> getPendingStudents(Long assignmentId, String teacherEmail) {
        Teacher teacher = getTeacher(teacherEmail);
        Assignment assignment = assignmentRepository.findByIdAndTeacherId(assignmentId, teacher.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found or unauthorized"));

        if (assignment.getBatch() == null) {
            return List.of();
        }
        List<Student> allStudents = studentRepository.findByBatchId(assignment.getBatch().getId());
        List<Long> submittedStudentIds = submissionRepository.findByAssignmentId(assignmentId).stream()
                .map(sub -> sub.getStudent().getId())
                .toList();

        List<Student> pendingStudents = allStudents.stream()
                .filter(student -> !submittedStudentIds.contains(student.getId()))
                .toList();

        return userMapper.toStudentResponseList(pendingStudents);
    }

    @Override
    @Transactional
    public SubmissionResponse reviewSubmission(Long submissionId, SubmissionReviewRequest request, String teacherEmail) {
        Teacher teacher = getTeacher(teacherEmail);
        Submission submission = submissionRepository.findByIdAndAssignmentTeacherId(submissionId, teacher.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found or unauthorized"));

        if (request.getMarks() > submission.getAssignment().getTotalMarks()) {
            throw new BadRequestException("Assigned marks cannot exceed total assignment marks of " + submission.getAssignment().getTotalMarks());
        }

        submission.setMarks(request.getMarks());
        submission.setFeedback(request.getFeedback());
        submission.setStatus(SubmissionStatus.REVIEWED);
        submission.setReviewedAt(LocalDateTime.now());

        Submission reviewedSubmission = submissionRepository.save(submission);
        try {
            certificateService.generateCertificateForSubmission(reviewedSubmission.getId());
        } catch (Exception e) {
            System.err.println("Failed to generate assignment certificate: " + e.getMessage());
        }
        return submissionMapper.toResponse(reviewedSubmission);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubmissionResponse> getStudentSubmissions(String studentEmail, int page, int size) {
        Student student = getStudent(studentEmail);
        Pageable pageable = PageRequest.of(page, size);
        Page<Submission> submissionPage = submissionRepository.findByStudentId(student.getId(), pageable);
        return submissionMapper.toResponseList(submissionPage.getContent());
    }

    @Override
    @Transactional(readOnly = true)
    public SubmissionResponse getSubmissionById(Long id, String email, String role) {
        Submission submission;
        if ("TEACHER".equals(role)) {
            Teacher teacher = getTeacher(email);
            submission = submissionRepository.findByIdAndAssignmentTeacherId(id, teacher.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Submission not found or unauthorized"));
        } else {
            Student student = getStudent(email);
            submission = submissionRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Submission not found"));
            if (!submission.getStudent().getId().equals(student.getId())) {
                throw new BadRequestException("Unauthorized to view this submission");
            }
        }
        return submissionMapper.toResponse(submission);
    }
}
