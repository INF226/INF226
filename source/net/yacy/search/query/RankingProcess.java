// RankingProcess.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 07.11.2007 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.search.query;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import net.yacy.cora.document.Classification.ContentDomain;
import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.cora.document.UTF8;
import net.yacy.cora.federate.yacy.CacheStrategy;
import net.yacy.cora.lod.JenaTripleStore;
import net.yacy.cora.lod.vocabulary.Tagging;
import net.yacy.cora.lod.vocabulary.YaCyMetadata;
import net.yacy.cora.sorting.ConcurrentScoreMap;
import net.yacy.cora.sorting.ScoreMap;
import net.yacy.cora.sorting.WeakPriorityBlockingQueue;
import net.yacy.cora.sorting.WeakPriorityBlockingQueue.ReverseElement;
import net.yacy.cora.storage.HandleSet;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.document.Condenser;
import net.yacy.document.LibraryProvider;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.data.meta.URIMetadataNode;
import net.yacy.kelondro.data.meta.URIMetadataRow;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.data.word.WordReference;
import net.yacy.kelondro.data.word.WordReferenceVars;
import net.yacy.kelondro.index.RowHandleSet;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.rwi.ReferenceContainer;
import net.yacy.kelondro.rwi.TermSearch;
import net.yacy.kelondro.util.Bitfield;
import net.yacy.peers.graphics.ProfilingGraph;
import net.yacy.search.EventTracker;
import net.yacy.search.Switchboard;
import net.yacy.search.index.Segment;
import net.yacy.search.ranking.ReferenceOrder;
import net.yacy.search.snippet.ResultEntry;

import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;

public final class RankingProcess extends Thread {

    protected static final int max_results_preparation = 3000, max_results_preparation_special = -1; // -1 means 'no limit'

    //private final SearchEvent searchEvent;
    private final QueryParams query;
    private SortedMap<byte[], ReferenceContainer<WordReference>> localSearchInclusion;
    private final ScoreMap<String> ref; // reference score computation for the commonSense heuristic
    private final long maxtime;
    protected final WeakPriorityBlockingQueue<WordReferenceVars> rwiStack;
    protected final ConcurrentHashMap<String, WeakPriorityBlockingQueue<WordReferenceVars>> doubleDomCache; // key = domhash (6 bytes); value = like stack
    private final int[] flagcount; // flag counter
    private final HandleSet misses; // contains url-hashes that could not been found in the LURL-DB
    private final AtomicInteger feedersAlive, feedersTerminated;
    private boolean addRunning;
    protected final AtomicInteger receivedRemoteReferences;
    protected final ReferenceOrder order;
    int local_indexCount;
    int remote_indexCount;
    int remote_resourceSize;
    int remote_peerCount;
    final HandleSet urlhashes; // map for double-check; String/Long relation, addresses ranking number (backreference for deletion)
    final ScoreMap<String> hostNavigator; // a counter for the appearance of the host hash
    final Map<String, byte[]> hostResolver; // a mapping from a host hash (6 bytes) to the full url hash of one of these urls that have the host hash
    final Map<String, String> taggingPredicates; // a map from tagging vocabulary names to tagging predicate uris
    final Map<String, ScoreMap<String>> vocabularyNavigator; // counters for Vocabularies; key is metatag.getVocabularyName()
    private boolean remote;
    
    public RankingProcess(final QueryParams query, boolean remote) {
        // we collect the urlhashes and construct a list with urlEntry objects
        // attention: if minEntries is too high, this method will not terminate within the maxTime
        // sortorder: 0 = hash, 1 = url, 2 = ranking
        this.query = query;
        this.remote = remote;
        this.localSearchInclusion = null;
        this.ref = new ConcurrentScoreMap<String>();
        this.maxtime = query.maxtime;
        int stackMaxsize = query.snippetCacheStrategy == null || query.snippetCacheStrategy == CacheStrategy.CACHEONLY ? max_results_preparation_special : max_results_preparation;
        this.rwiStack = new WeakPriorityBlockingQueue<WordReferenceVars>(stackMaxsize, false);
        this.doubleDomCache = new ConcurrentHashMap<String, WeakPriorityBlockingQueue<WordReferenceVars>>();
        this.misses = new RowHandleSet(URIMetadataRow.rowdef.primaryKeyLength, URIMetadataRow.rowdef.objectOrder, 100);
        this.flagcount = new int[32];
        for ( int i = 0; i < 32; i++ ) {
            this.flagcount[i] = 0;
        }
        this.feedersAlive = new AtomicInteger(0);
        this.feedersTerminated = new AtomicInteger(0);
        this.addRunning = true;
        this.receivedRemoteReferences = new AtomicInteger(0);
        this.order = new ReferenceOrder(this.query.ranking, UTF8.getBytes(this.query.targetlang));
        this.remote_resourceSize = 0;
        this.remote_indexCount = 0;
        this.remote_peerCount = 0;
        this.local_indexCount = 0;
        this.urlhashes = new RowHandleSet(URIMetadataRow.rowdef.primaryKeyLength, URIMetadataRow.rowdef.objectOrder, 100);
        this.hostNavigator = new ConcurrentScoreMap<String>();
        this.hostResolver = new ConcurrentHashMap<String, byte[]>();
        this.vocabularyNavigator = new ConcurrentHashMap<String, ScoreMap<String>>();
        this.taggingPredicates = new HashMap<String, String>();
        for (Tagging t: LibraryProvider.autotagging.getVocabularies()) {
            this.taggingPredicates.put(t.getName(), t.getPredicate());
        }
    }

    public ReferenceOrder getOrder() {
        return this.order;
    }
    
    protected boolean feedingIsFinished() {
        return
            this.feedersTerminated.intValue() > (this.remote ? 1 : 0) &&
            this.feedersAlive.get() == 0;// &&
            //(!this.remote || this.remote_indexCount > 0);
    }

    /**
     * method to signal the incoming stack that one feeder has terminated
     */
    public void oneFeederTerminated() {
        this.feedersTerminated.incrementAndGet();
        final int c = this.feedersAlive.decrementAndGet();
        assert c >= 0 : "feeders = " + c;
    }

    public void oneFeederStarted() {
        this.feedersAlive.addAndGet(1);
    }
    
    public int rwiAvailableCount() {
        // the number of results in the local peer after filtering
        return this.rwiStack.sizeAvailable();
    }
    
    public QueryParams getQuery() {
        return this.query;
    }

    public int[] flagCount() {
        return this.flagcount;
    }

    public Iterator<byte[]> miss() {
        return this.misses.iterator();
    }

    public int getMissCount() {
        return this.misses.size();
    }

    protected void addBegin() {
        this.addRunning = true;
    }

    public void addFinalize() {
        this.addRunning = false;
    }

    protected boolean addRunning() {
        return this.addRunning;
    }
    
    public boolean rwiIsEmpty() {
        if ( !this.rwiStack.isEmpty() ) {
            return false;
        }
        for ( final WeakPriorityBlockingQueue<WordReferenceVars> s : this.doubleDomCache.values() ) {
            if ( !s.isEmpty() ) {
                return false;
            }
        }
        return true;
    }

    protected int rwiQueueSize() {
        int c = this.rwiStack.sizeQueue();
        for ( final WeakPriorityBlockingQueue<WordReferenceVars> s : this.doubleDomCache.values() ) {
            c += s.sizeQueue();
        }
        return c;
    }
    
    protected boolean testFlags(final Bitfield flags) {
        if (this.query.constraint == null) return true;
        // test if ientry matches with filter
        // if all = true: let only entries pass that has all matching bits
        // if all = false: let all entries pass that has at least one matching bit
        if (this.query.allofconstraint) {
            for ( int i = 0; i < 32; i++ ) {
                if ((this.query.constraint.get(i)) && (!flags.get(i))) return false;
            }
            return true;
        }
        for (int i = 0; i < 32; i++) {
            if ((this.query.constraint.get(i)) && (flags.get(i))) return true;
        }
        return false;
    }

    public int getRemoteIndexCount() {
        // the number of result contributions from all the remote peers
        return this.remote_indexCount;
    }

    public int getRemoteResourceSize() {
        // the number of all hits in all the remote peers
        return Math.max(this.remote_resourceSize, this.remote_indexCount);
    }

    public int getRemotePeerCount() {
        // the number of remote peers that have contributed
        return this.remote_peerCount;
    }

    @Override
    public void run() {

        // do a search
        oneFeederStarted();

        // sort the local containers and truncate it to a limited count,
        // so following sortings together with the global results will be fast
        try {
            final long timer = System.currentTimeMillis();
            final TermSearch<WordReference> search =
                this.query
                    .getSegment()
                    .termIndex()
                    .query(
                        this.query.query_include_hashes,
                        this.query.query_exclude_hashes,
                        null,
                        Segment.wordReferenceFactory,
                        this.query.maxDistance);
            this.localSearchInclusion = search.inclusion();
            final ReferenceContainer<WordReference> index = search.joined();
            EventTracker.update(
                EventTracker.EClass.SEARCH,
                new ProfilingGraph.EventSearch(
                    this.query.id(true),
                    SearchEventType.JOIN,
                    this.query.queryString,
                    index.size(),
                    System.currentTimeMillis() - timer),
                false);
            if ( !index.isEmpty() ) {
                add(index, true, "local index: " + this.query.getSegment().getLocation(), -1, this.maxtime);
                this.addFinalize();
            }
        } catch ( final Exception e ) {
            Log.logException(e);
        } finally {
            oneFeederTerminated();
        }
    }

    public void add(
        final ReferenceContainer<WordReference> index,
        final boolean local,
        final String resourceName,
        final int fullResource,
        final long maxtime) {
        // we collect the urlhashes and construct a list with urlEntry objects
        // attention: if minEntries is too high, this method will not terminate within the maxTime
        //Log.logInfo("RWIProcess", "added a container, size = " + index.size());

        this.addRunning = true;
        assert (index != null);
        if (index.isEmpty()) return;
        if (!local) {
            assert fullResource >= 0 : "fullResource = " + fullResource;
            this.remote_resourceSize += fullResource;
            this.remote_peerCount++;
        }
        long timer = System.currentTimeMillis();

        // normalize entries
        final BlockingQueue<WordReferenceVars> decodedEntries = this.order.normalizeWith(index, maxtime);
        int is = index.size();
        EventTracker.update(EventTracker.EClass.SEARCH, new ProfilingGraph.EventSearch(
            this.query.id(true),
            SearchEventType.NORMALIZING,
            resourceName,
            is,
            System.currentTimeMillis() - timer), false);
        if (!local) this.receivedRemoteReferences.addAndGet(is);

        // iterate over normalized entries and select some that are better than currently stored
        timer = System.currentTimeMillis();
        final boolean nav_hosts = this.query.navigators.equals("all") || this.query.navigators.indexOf("hosts", 0) >= 0;

        // apply all constraints
        long timeout = System.currentTimeMillis() + maxtime;
        try {
            WordReferenceVars iEntry;
            final String pattern = this.query.urlMask.pattern();
            final boolean httpPattern = pattern.equals("http://.*");
            final boolean noHttpButProtocolPattern = pattern.equals("https://.*") || pattern.equals("ftp://.*") || pattern.equals("smb://.*") || pattern.equals("file://.*");
            long remaining;
            pollloop: while ( true ) {
                remaining = timeout - System.currentTimeMillis();
                if (remaining <= 0) {
                    Log.logWarning("RWIProcess", "terminated 'add' loop before poll time-out = " + remaining + ", decodedEntries.size = " + decodedEntries.size());
                    break;
                }
                iEntry = decodedEntries.poll(remaining, TimeUnit.MILLISECONDS);
                if (iEntry == null) {
                    Log.logWarning("RWIProcess", "terminated 'add' loop after poll time-out = " + remaining + ", decodedEntries.size = " + decodedEntries.size());
                    break pollloop;
                }
                if (iEntry == WordReferenceVars.poison) {
                    break pollloop;
                }
                assert (iEntry.urlhash().length == index.row().primaryKeyLength);

                // doublecheck for urls
                if (this.urlhashes.has(iEntry.urlhash())) continue pollloop;

                // increase flag counts
                Bitfield flags = iEntry.flags();
                for (int j = 0; j < 32; j++) {
                    if (flags.get(j)) this.flagcount[j]++;
                }

                // check constraints
                if (!this.testFlags(flags)) continue pollloop;

                // check document domain
                if (this.query.contentdom.getCode() > 0 &&
                    ((this.query.contentdom == ContentDomain.AUDIO && !(flags.get(Condenser.flag_cat_hasaudio))) || 
                     (this.query.contentdom == ContentDomain.VIDEO && !(flags.get(Condenser.flag_cat_hasvideo))) ||
                     (this.query.contentdom == ContentDomain.IMAGE && !(flags.get(Condenser.flag_cat_hasimage))) ||
                     (this.query.contentdom == ContentDomain.APP && !(flags.get(Condenser.flag_cat_hasapp))))) {
                    continue pollloop;
                }

                // count domZones
                //this.domZones[DigestURI.domDomain(iEntry.metadataHash())]++;

                // check site constraints
                final String hosthash = iEntry.hosthash();
                if ( this.query.sitehash == null ) {
                    if (this.query.siteexcludes != null && this.query.siteexcludes.contains(hosthash)) continue pollloop;
                } else {
                    // filter out all domains that do not match with the site constraint
                    if (!hosthash.equals(this.query.sitehash)) continue pollloop;
                }

                // collect host navigation information (even if we have only one; this is to provide a switch-off button)
                if (this.query.navigators.isEmpty() && (nav_hosts || this.query.urlMask_isCatchall)) {
                    this.hostNavigator.inc(hosthash);
                    this.hostResolver.put(hosthash, iEntry.urlhash());
                }

                // check protocol
                if (!this.query.urlMask_isCatchall) {
                    final boolean httpFlagSet = DigestURI.flag4HTTPset(iEntry.urlHash);
                    if (httpPattern && !httpFlagSet) continue pollloop;
                    if (noHttpButProtocolPattern && httpFlagSet) continue pollloop;
                }

                // check vocabulary constraint
                String subject = YaCyMetadata.hashURI(iEntry.urlhash());
                Resource resource = JenaTripleStore.getResource(subject);
                if (this.query.metatags != null && !this.query.metatags.isEmpty()) {
                    // all metatags must appear in the tags list
                    for (Tagging.Metatag metatag: this.query.metatags) {
                        Iterator<RDFNode> ni = JenaTripleStore.getObjects(resource, metatag.getPredicate());
                        if (!ni.hasNext()) continue pollloop;
                        String tags = ni.next().toString();
                        if (tags.indexOf(metatag.getObject()) < 0) continue pollloop;
                    }
                }

                // add navigators using the triplestore
                for (Map.Entry<String, String> v: this.taggingPredicates.entrySet()) {
                    Iterator<RDFNode> ni = JenaTripleStore.getObjects(resource, v.getValue());
                    while (ni.hasNext()) {
                        String[] tags = ni.next().toString().split(",");
                        for (String tag: tags) {
                            ScoreMap<String> voc = this.vocabularyNavigator.get(v.getKey());
                            if (voc == null) {
                                voc = new ConcurrentScoreMap<String>();
                                this.vocabularyNavigator.put(v.getKey(), voc);
                            }
                            voc.inc(tag);
                        }
                    }
                }

                // finally extend the double-check and insert result to stack
                this.urlhashes.putUnique(iEntry.urlhash());
                rankingtryloop: while (true) {
                    try {
                        this.rwiStack.put(new ReverseElement<WordReferenceVars>(iEntry, this.order.cardinal(iEntry))); // inserts the element and removes the worst (which is smallest)
                        break rankingtryloop;
                    } catch ( final ArithmeticException e ) {
                        // this may happen if the concurrent normalizer changes values during cardinal computation
                        continue rankingtryloop;
                    }
                }
                // increase counter for statistics
                if (local) this.local_indexCount++; else this.remote_indexCount++;
            }
            if (System.currentTimeMillis() >= timeout) Log.logWarning("RWIProcess", "rwi normalization ended with timeout = " + maxtime);

        } catch ( final InterruptedException e ) {
        } catch ( final SpaceExceededException e ) {
        }

        //if ((query.neededResults() > 0) && (container.size() > query.neededResults())) remove(true, true);
        EventTracker.update(EventTracker.EClass.SEARCH, new ProfilingGraph.EventSearch(
            this.query.id(true),
            SearchEventType.PRESORT,
            resourceName,
            index.size(),
            System.currentTimeMillis() - timer), false);
    }
    
    protected Map<byte[], ReferenceContainer<WordReference>> searchContainerMap() {
        // direct access to the result maps is needed for abstract generation
        // this is only available if execQuery() was called before
        return this.localSearchInclusion;
    }

    
    public ScoreMap<String> getHostNavigator() {
        final ScoreMap<String> result = new ConcurrentScoreMap<String>();
        if ( !this.query.navigators.equals("all") && this.query.navigators.indexOf("hosts", 0) < 0 ) {
            return result;
        }

        final Iterator<String> domhashs = this.hostNavigator.keys(false);
        URIMetadataNode row;
        byte[] urlhash;
        String hosthash, hostname;
        if ( this.hostResolver != null ) {
            while ( domhashs.hasNext() && result.sizeSmaller(30) ) {
                hosthash = domhashs.next();
                if ( hosthash == null ) {
                    continue;
                }
                urlhash = this.hostResolver.get(hosthash);
                row = urlhash == null ? null : this.query.getSegment().fulltext().getMetadata(urlhash);
                hostname = row == null ? null : row.url().getHost();
                if ( hostname != null ) {
                    result.set(hostname, this.hostNavigator.get(hosthash));
                }
            }
        }
        return result;
    }

    public Map<String,ScoreMap<String>> getVocabularyNavigators() {
        return this.vocabularyNavigator;
    }

    protected ScoreMap<String> getTopicNavigator(final int count) {
        // create a list of words that had been computed by statistics over all
        // words that appeared in the url or the description of all urls
        final ScoreMap<String> result = new ConcurrentScoreMap<String>();
        if ( !this.query.navigators.equals("all") && this.query.navigators.indexOf("topics", 0) < 0 ) {
            return result;
        }
        if ( this.ref.sizeSmaller(2) ) {
            this.ref.clear(); // navigators with one entry are not useful
        }
        final Map<String, Float> counts = new HashMap<String, Float>();
        final Iterator<String> i = this.ref.keys(false);
        String word;
        int c;
        float q, min = Float.MAX_VALUE, max = Float.MIN_VALUE;
        int ic = count;
        while ( ic-- > 0 && i.hasNext() ) {
            word = i.next();
            if ( word == null ) {
                continue;
            }
            c = this.query.getSegment().getQueryCount(word);
            if ( c > 0 ) {
                q = ((float) this.ref.get(word)) / ((float) c);
                min = Math.min(min, q);
                max = Math.max(max, q);
                counts.put(word, q);
            }
        }
        if ( max > min ) {
            for ( final Map.Entry<String, Float> ce : counts.entrySet() ) {
                result.set(ce.getKey(), (int) (((double) count) * (ce.getValue() - min) / (max - min)));
            }
        }
        return this.ref;
    }

    private final static Pattern lettermatch = Pattern.compile("[a-z]+");

    public void addTopic(final String[] words) {
        String word;
        for ( final String w : words ) {
            word = w.toLowerCase();
            if ( word.length() > 2
                && "http_html_php_ftp_www_com_org_net_gov_edu_index_home_page_for_usage_the_and_zum_der_die_das_und_the_zur_bzw_mit_blog_wiki_aus_bei_off"
                    .indexOf(word) < 0
                && !this.query.query_include_hashes.has(Word.word2hash(word))
                && lettermatch.matcher(word).matches()
                && !Switchboard.badwords.contains(word)
                && !Switchboard.stopwords.contains(word) ) {
                this.ref.inc(word);
            }
        }
    }

    protected void addTopics(final ResultEntry resultEntry) {
        // take out relevant information for reference computation
        if ( (resultEntry.url() == null) || (resultEntry.title() == null) ) {
            return;
        }
        //final String[] urlcomps = htmlFilterContentScraper.urlComps(resultEntry.url().toNormalform(true, true)); // word components of the url
        final String[] descrcomps = MultiProtocolURI.splitpattern.split(resultEntry.title().toLowerCase()); // words in the description

        // add references
        //addTopic(urlcomps);
        addTopic(descrcomps);
    }


}