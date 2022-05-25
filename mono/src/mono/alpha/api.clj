(ns mono.alpha.api
  "Extra tasks for project diff"
  (:require
    [babashka.fs :as fs]
    [clojure.pprint :as pp]
    [clojure.string :as string]
    [clojure.tools.build.api :as b]))


(set! *warn-on-reflection* true)


(defn canonical-path
  "Canonical path in string"
  [& paths]
  (->> (apply fs/file paths)
       (fs/canonicalize)
       (str)))


(defn git-root
  "Git root of the current directory"
  []
  (-> (b/git-process {:git-args ["rev-parse" "--show-toplevel"]})
      (canonical-path)))


(def ^:dynamic *repo-root* (git-root))


(defn set-repo-root!
  [root]
  (alter-var-root #'*repo-root* (constantly root)))


(defn local-root-deps
  "Get all the `:local/root` deps under the *repo-root*."
  [{:keys [basis]}]
  (let [xf (comp (map (fn [[lib-name lib-info]]
                        (when (not= :mvn (:deps/manifest lib-info))
                          (assoc lib-info :lib/name lib-name))))
                 (filter identity)
                 (filter #(fs/starts-with? (:deps/root %) (canonical-path *repo-root*))))]
    (into [] xf (:libs basis))))


(defn deps
  "deps plus `:paths` as a deps"
  [{:keys [basis] :as params}]
  (conj (local-root-deps params)
        {:paths (->> basis :paths (map (partial canonical-path b/*project-root*)))
         :deps/root b/*project-root*
         :deps/manifest :self}))


(defn changed-files
  "List all changed files between commit1 and commit2"
  [commit1 commit2]
  (let [diffs (some->> (b/git-process {:git-args (cond-> ["diff" "--name-only"]
                                                   commit1 (conj commit1)
                                                   commit2 (conj commit2))
                                       :dir (canonical-path *repo-root*)})
                       (string/split-lines)
                       (map (partial canonical-path *repo-root*)))]
    diffs))


(defn under-deps?
  "Check if a canonical path is under the deps"
  [deps path]
  (let [paths (cond-> (:paths deps)
                (#{:deps :self} (:deps/manifest deps))
                (conj (canonical-path (:deps/root deps) "deps.edn")))]
    (some #(fs/starts-with? path %) paths)))


(defn assoc-changes
  [changed-files deps]
  (assoc deps :changes (filter (partial under-deps? deps) changed-files)))


(defn deps-changes
  "Get changed deps bases on git changed files"
  [{:keys [changes deps keep-no-change?]}]
  (let [xf (cond-> (map (partial assoc-changes changes))
             (not keep-no-change?)
             (comp (filter (comp seq :changes))))]
    (into [] xf deps)))


(defn last-tags
  "Get last n tags order by committerdate desc"
  ([pattern] (last-tags pattern 2))
  ([pattern n]
   (let [tags (-> (b/git-process {:git-args ["tag" "--sort=-creatordate" "--list" pattern]
                                  :dir (canonical-path *repo-root*)})
                  (string/split-lines))]
     (take n tags))))


(defn current-sha
  "Get HEAD sha"
  []
  (b/git-process {:git-args ["rev-parse" "HEAD"]}))


(defn all-deps-edn
  "List all deps.edn in repo"
  []
  (->> (fs/match *repo-root* "glob:**deps.edn" {:recursive true})
       (map canonical-path)))


(defn info
  []
  {:repo-root (canonical-path *repo-root*)
   :project (str (fs/relativize (canonical-path *repo-root*) (fs/canonicalize b/*project-root*)))})


(defn print-kv
  "Print key value as table"
  [m cols]
  (pp/print-table cols (map (partial zipmap cols) m)))


(defn print-changes
  [changes]
  (pp/print-table [:deps :changed-files]
                  (map (fn [c]
                         {:deps (:lib/name c)
                          :changed-files (count (:changes c))})
                       changes)))
