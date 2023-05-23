(ns mono.alpha.api
  "Extra tasks for project diff"
  (:require
   [mono.alpha.git :as git]
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [clojure.tools.build.api :as b]))


(defn local-root-deps
  "Get all the `:local/root` deps under the *repo-root*."
  [{:keys [basis]}]
  (let [xf (comp (map (fn [[lib-name lib-info]]
                        (when (not= :mvn (:deps/manifest lib-info))
                          (assoc lib-info :lib/name lib-name))))
                 (filter identity)
                 (filter #(fs/starts-with? (:deps/root %) (git/canonical-path git/*repo-root*))))]
    (into [] xf (:libs basis))))


(defn deps
  "deps plus `:paths` as a deps"
  [{:keys [basis] :as params}]
  (conj (local-root-deps params)
        {:paths (->> basis :paths (map (partial git/canonical-path b/*project-root*)))
         :deps/root b/*project-root*
         :deps/manifest :self}))


(defn under-deps?
  "Check if a canonical path is under the deps"
  [deps path]
  (let [paths (cond-> (:paths deps)
                (#{:deps :self} (:deps/manifest deps))
                (conj (git/canonical-path (:deps/root deps) "deps.edn")))]
    (some #(fs/starts-with? path %) paths)))


(defn assoc-changes
  [changed-files deps]
  (assoc deps :changes (filter (partial under-deps? deps) changed-files)))


(defn deps-changes
  "Diff git changed files and concerned paths"
  [{:keys [changes deps keep-no-change?]}]
  (let [xf (cond-> (map (partial assoc-changes changes))
             (not keep-no-change?)
             (comp (filter (comp seq :changes))))]
    (into [] xf deps)))


(defn all-deps-edn
  "List all deps.edn in repo"
  []
  (->> (fs/match git/*repo-root* "glob:**deps.edn" {:recursive true})
       (map git/canonical-path)))


(defn info
  []
  {:repo-root (git/canonical-path git/*repo-root*)
   :project (str (fs/relativize (git/canonical-path git/*repo-root*) (fs/canonicalize b/*project-root*)))})


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


(defn changes?
  "Return true if any change is detected. Otherwise false. 
   Will stop at the first change detected."
  [path {:keys [related? changed? reporter]}]
  (when (and (related? path) (changed? path))
    path))


(defn get-changes
  "Get list of changes detected base on current config."
  [git-changes detection-config])

(comment
  (compare
   git/changes
   [[(deps/paths "deps.edn") (general/file-changed)]
    [(deps/local-roots "deps.edn")]
    [(general/file "Dockerfile") (general/file-changed)]]))