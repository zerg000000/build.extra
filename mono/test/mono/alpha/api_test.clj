(ns mono.alpha.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [mono.alpha.api :as mono]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [clojure.string :as string]))


(defn local-root [lib-name]
  {:local/root (str "../../" (str lib-name))})


(defn deps-edn [libs]
  {:paths ["src" "resources"]
   :deps (into {}
               (for [lib libs]
                 [lib (local-root lib)]))})


(defn create-fake-deps-project [deps-project-name libs]
  (let [ns (string/replace deps-project-name #"^.+/" "")]
    {:deps-name deps-project-name
     :files [{:type :dir :filename (str "src/" ns)}
             {:type :file :filename "deps.edn" :content (str (deps-edn libs))}
             {:type :file :filename (str "src/" ns "/core.clj") :content (str "(ns " ns ".core)")}
             {:type :dir :filename "resources"}]}))

(defn exec-create-project [{:keys [deps-name files]} repo-root]
  (doseq [{:keys [type filename content]} files]
    (case type
      :dir (fs/create-dirs (fs/file repo-root deps-name filename))
      :file (spit (fs/file repo-root deps-name filename) content))))

(defn setup-test-repo []
  (let [repo-root (fs/create-temp-dir)]
    (doseq [project [(create-fake-deps-project "libs/common" [])
                     (create-fake-deps-project "libs/web" [])
                     (create-fake-deps-project "libs/logging" [])
                     (create-fake-deps-project "libs/frontend" [])
                     (create-fake-deps-project "artifacts/service-a" ['libs/common 'libs/web 'libs/logging])
                     (create-fake-deps-project "artifacts/service-b" ['libs/common 'libs/frontend 'libs/logging])
                     (create-fake-deps-project "artifacts/schedule-job-c" ['libs/common])]]
      (exec-create-project project repo-root))
    (spit (fs/file repo-root ".gitignore") "**/.cpcache/\n")
    (b/git-process {:git-args ["init"]
                    :dir (str repo-root)})
    (b/git-process {:git-args ["add" "."]
                    :dir (str repo-root)})
    (b/git-process {:git-args ["commit" "-am" "initial commit"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    (b/git-process {:git-args ["tag" "-a" "v0.0.1" "-m" "baseline"]
                    :dir (str repo-root)})
    (spit (fs/file repo-root "libs/common/src/common/core.clj") ";; changes " :append true)
    (b/git-process {:git-args ["commit" "-am" "change common, all artifacts should be rebuilt"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    (b/git-process {:git-args ["tag" "-a" "v0.0.2" "-m" "baseline"]
                    :dir (str repo-root)})
    (spit (fs/file repo-root "libs/web/src/web/core.clj") ";; changes " :append true)
    (b/git-process {:git-args ["commit" "-am" "change web, service-a should be rebuilt"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    (b/git-process {:git-args ["tag" "-a" "v0.0.3" "-m" "baseline"]
                    :dir (str repo-root)})
    (spit (fs/file repo-root "artifacts/schedule-job-c/src/schedule-job-c/core.clj") ";; changes " :append true)
    (b/git-process {:git-args ["commit" "-am" "change schedule-job-c, schedule-job-c should be rebuilt"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    (b/git-process {:git-args ["tag" "-a" "v0.0.4" "-m" "baseline"]
                    :dir (str repo-root)})
    (exec-create-project (create-fake-deps-project "libs/email" []) repo-root)
    (spit (fs/file repo-root "artifacts/schedule-job-c/deps.edn") (str (deps-edn ['libs/common 'libs/email])))
    (b/git-process {:git-args ["commit" "-am" "add email to schedule-job-c, schedule-job-c should be rebuilt"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    (b/git-process {:git-args ["tag" "-a" "v0.0.5" "-m" "baseline"]
                    :dir (str repo-root)})
    (spit (fs/file repo-root "artifacts/service-b/deps.edn") "\n\n" :append true)
    (b/git-process {:git-args ["commit" "-am" "change service-b's deps, service-b should be rebuilt"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    (b/git-process {:git-args ["tag" "-a" "v0.0.6" "-m" "baseline"]
                    :dir (str repo-root)})
    (Thread/sleep 1000)
    repo-root))

(deftest mono-test
  (let [git-root (setup-test-repo)]
    (testing "utils should be work as expected"
      (is (not= git-root mono/*repo-root*))
      (mono/set-repo-root! git-root)
      (is (= git-root mono/*repo-root*) "should equal to git-root after set-repo-root!")
      (is (= ["v0.0.6" "v0.0.5" "v0.0.4" "v0.0.3" "v0.0.2" "v0.0.1"] 
             (mono/last-tags "v*" 10))
          "should return v0.0.5..v0.0.1"))
    (testing "all artifacts rebuilt, when common deps is changed"

      (b/set-project-root! (str git-root "/artifacts/service-a"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.1" "v0.0.2")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/service-b"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.1" "v0.0.2")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/schedule-job-c"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.1" "v0.0.2")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})})))))
    
    (testing "service-a rebuilt, when web deps is changed"

      (b/set-project-root! (str git-root "/artifacts/service-a"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.2" "v0.0.3")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/service-b"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.2" "v0.0.3")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/schedule-job-c"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.2" "v0.0.3")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})})))))
    
    (testing "schedule-job-c rebuilt, when schedule-job-c source is changed"

      (b/set-project-root! (str git-root "/artifacts/service-a"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.3" "v0.0.4")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/service-b"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.3" "v0.0.4")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/schedule-job-c"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.3" "v0.0.4")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})})))))
    
    (testing "schedule-job-c rebuilt, when new deps email is added to schedule-job-c"

      (b/set-project-root! (str git-root "/artifacts/service-a"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.4" "v0.0.5")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/service-b"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.4" "v0.0.5")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/schedule-job-c"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.4" "v0.0.5")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})})))))
    
    (testing "service-b rebuilt, when service-b's deps.edn is changed"

      (b/set-project-root! (str git-root "/artifacts/service-a"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.5" "v0.0.6")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/service-b"))
      (is (= 1 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.5" "v0.0.6")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})}))))
      (b/set-project-root! (str git-root "/artifacts/schedule-job-c"))
      (is (= 0 (count (mono/deps-changes {:changes (mono/changed-files "v0.0.5" "v0.0.6")
                                          :deps (mono/deps {:basis (b/create-basis
                                                                    {})})})))))))