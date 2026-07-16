import React, { useEffect, useState, useMemo } from 'react';
import { motion } from 'framer-motion';
import { Search, Filter, LayoutGrid, List, ArrowRight, BookOpen, Clock, PlayCircle, Layers } from 'lucide-react';
import { Layout } from '../../components/layout/Layout';
import { useCatalog } from '../../hooks-lms/useCatalog';
import { studentService } from '../../services/student.service';
import { Link } from 'react-router-dom';

export const StudentMyCoursesPage: React.FC = () => {
  const { courses, categories } = useCatalog() as any;
  const [search, setSearch] = useState('');
  const [view, setView] = useState('grid');
  const [selectedCategory, setSelectedCategory] = useState('All');
  const [enrollments, setEnrollments] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    const fetchEnrollments = async () => {
      try {
        const res = await studentService.getMyCourseEnrollments(0, 1000);
        if (active) {
          const content = res?.data?.content || res?.content || [];
          setEnrollments(content);
        }
      } catch (err) {
        console.error('Error fetching enrollments:', err);
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };
    fetchEnrollments();
    return () => { active = false; };
  }, []);

  // Filter courses that are APPROVED
  const approvedCourses = useMemo(() => {
    const approvedCourseTitlesOrIds = new Set(
      enrollments
        .filter((e) => e.status === 'APPROVED')
        .map((e) => String(e.courseId || e.courseName))
    );

    return (courses || []).filter((c) => 
      approvedCourseTitlesOrIds.has(String(c.id)) || approvedCourseTitlesOrIds.has(c.title)
    );
  }, [courses, enrollments]);

  const filteredCourses = useMemo(() => {
    return approvedCourses.filter((course) => {
      const categoryObj = course.category || (categories || []).find(cat => cat.id === course.categoryId);
      const catName = categoryObj?.name || categoryObj || '';
      const matchesSearch = course.title.toLowerCase().includes(search.toLowerCase()) || catName.toLowerCase().includes(search.toLowerCase());
      const matchesCategory = selectedCategory === 'All' || catName.toLowerCase() === selectedCategory.toLowerCase();
      return matchesSearch && matchesCategory;
    });
  }, [approvedCourses, search, selectedCategory, categories]);

  return (
    <Layout role="student" title="My Courses" subtitle="Manage and access your approved learning paths.">
      {/* Search and filter bar */}
      <div className="flex flex-col gap-4 rounded-3xl border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-sm lg:flex-row lg:items-center lg:justify-between mb-8">
        <div className="relative w-full lg:w-80">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search enrolled courses..."
            className="w-full rounded-full border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-950 px-4 py-2 pl-10 text-xs font-bold text-slate-800 dark:text-slate-100 outline-none focus:border-[#4A1F4F]"
          />
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 rounded-full border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-950 px-3.5 py-1.5 text-xs font-bold text-slate-700 dark:text-slate-400">
            <Filter className="h-3.5 w-3.5 text-[#4A1F4F]" />
            <select
              value={selectedCategory}
              onChange={(e) => setSelectedCategory(e.target.value)}
              className="bg-transparent text-xs font-bold outline-none cursor-pointer"
            >
              <option value="All">All Categories</option>
              {categories?.map((cat) => (
                <option key={cat.id} value={cat.name}>{cat.name}</option>
              ))}
            </select>
          </label>

          <div className="flex rounded-full border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-950 p-1">
            <button
              type="button"
              onClick={() => setView('grid')}
              className={`rounded-full p-2 cursor-pointer transition-colors ${view === 'grid' ? 'bg-[#4A1F4F] text-white shadow-sm' : 'text-slate-400'}`}
            >
              <LayoutGrid className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={() => setView('list')}
              className={`rounded-full p-2 cursor-pointer transition-colors ${view === 'list' ? 'bg-[#4A1F4F] text-white shadow-sm' : 'text-slate-400'}`}
            >
              <List className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Courses Display */}
      {loading ? (
        <div className="grid gap-6 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-96 rounded-[24px] border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 animate-pulse p-6 space-y-4">
              <div className="h-40 bg-slate-200 dark:bg-slate-800 rounded-2xl w-full" />
              <div className="h-6 bg-slate-200 dark:bg-slate-850 rounded w-2/3" />
              <div className="h-4 bg-slate-200 dark:bg-slate-850 rounded w-1/2" />
              <div className="h-10 bg-slate-200 dark:bg-slate-850 rounded-xl w-full mt-4" />
            </div>
          ))}
        </div>
      ) : filteredCourses.length === 0 ? (
        <div className="text-center py-16 px-4 rounded-[32px] border border-dashed border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 max-w-md mx-auto">
          <BookOpen className="h-12 w-12 text-[#4A1F4F] mx-auto mb-4 opacity-75" />
          <h3 className="text-lg font-bold text-slate-800 dark:text-slate-100">No Approved Courses</h3>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-2 leading-relaxed">
            You don't have any approved course enrollments yet. Go to <Link to="/student/courses" className="text-[#4A1F4F] dark:text-purple-400 font-bold hover:underline">All Courses</Link> to enroll.
          </p>
        </div>
      ) : (
        <div className={`grid gap-6 ${view === 'grid' ? 'md:grid-cols-2 xl:grid-cols-3' : 'grid-cols-1'}`}>
          {filteredCourses.map((course) => {
            const categoryObj = course.category || (categories || []).find(cat => cat.id === course.categoryId);
            const categoryName = categoryObj?.name || 'General';
            const totalModules = course.modules?.length || 0;
            const totalLessons = course.modules?.reduce((acc, m) => acc + (m.submodules?.length || 0), 0) || 0;

            return (
              <motion.article
                key={course.id}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                className="overflow-hidden rounded-[24px] border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-sm hover:shadow-xl hover:border-purple-300 dark:hover:border-purple-900 transition-all flex flex-col justify-between group"
              >
                <div>
                  <div className="relative h-44 w-full bg-slate-900 overflow-hidden">
                    <img
                      src={course.thumbnail || 'https://images.unsplash.com/photo-1516321318423-f06f85e504b3?w=800&auto=format&fit=crop&q=80'}
                      alt={course.title}
                      className="h-full w-full object-cover group-hover:scale-105 transition-transform duration-500"
                    />
                    <div className="absolute top-3 left-3 px-3 py-1 rounded-full text-[10px] font-extrabold uppercase bg-white/90 backdrop-blur-md text-purple-900 shadow-sm">
                      {categoryName}
                    </div>
                  </div>

                  <div className="p-6 space-y-3">
                    <h3 className="text-base font-black text-slate-800 dark:text-slate-100 group-hover:text-purple-600 transition-colors line-clamp-1">
                      {course.title}
                    </h3>
                    <p className="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider">
                      Instructor: <span className="text-[#10B5A5] font-extrabold">{course.author || 'Xebia Specialist'}</span>
                    </p>
                    <p className="text-xs text-slate-500 dark:text-slate-400 line-clamp-2 leading-relaxed">
                      {course.shortDescription || course.description || 'No course overview provided yet.'}
                    </p>

                    <div className="grid grid-cols-3 gap-2 pt-2 text-[10px] font-extrabold text-slate-500 dark:text-slate-400 border-t border-slate-100 dark:border-slate-800">
                      <div className="flex items-center gap-1.5">
                        <Clock className="h-3.5 w-3.5 text-purple-500 shrink-0" />
                        <span>{course.duration || '8w'}</span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <BookOpen className="h-3.5 w-3.5 text-purple-500 shrink-0" />
                        <span>{totalModules} Modules</span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <Layers className="h-3.5 w-3.5 text-purple-500 shrink-0" />
                        <span>{totalLessons} Lessons</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div className="p-6 pt-0">
                  <Link
                    to={`/student/courses/${course.id}`}
                    className="w-full flex items-center justify-center gap-2 rounded-xl py-2.5 text-xs font-bold text-white shadow-md transition-all hover:opacity-90 cursor-pointer"
                    style={{ backgroundColor: '#7C3AED' }}
                  >
                    <PlayCircle className="h-4 w-4" /> Start Learning Path <ArrowRight className="h-3.5 w-3.5" />
                  </Link>
                </div>
              </motion.article>
            );
          })}
        </div>
      )}
    </Layout>
  );
};
