package technology.tabula.detectors;

import java.awt.geom.Point2D;

import java.io.IOException;
import java.nio.Buffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.TextElement;

import java.text.SimpleDateFormat;
import java.io.FileWriter;
import java.io.BufferedWriter;

/*
 * RegexSearch
 * 
 *    This class supports regex-based content extraction from PDF Documents
 *    
 *    RegexSearch uses Apache's PDDocument class in conjunction with Java's standard Matcher and Pattern classes
 *    to provide a document-wide regex-based table search upon initialization. The areas of the document that correspond
 *    to the regex search can be obtained for a given page with the method getSubSectionsForPage(page #).
 *    NOTE: The term matching area is used to denote the entire region that the search spans,
 *    whereas the term subsection is used to denote a component of the matching area corresponding to a given search.
 *    Consider the following example:
 *
 *    	-Pattern Before Table: A
 *    	-Pattern After Table: B
 *
 *      data data data data data
 *		A                           <-- Start of area to extract                                         | Subsection #1
 *	    data data data data data	<-- Start of area to extract if(includeRegexBeforeTable==false)      | Subsection #1
 *      data data data data data                                                                         | Subsection #1
 *      data data data data data                                                                         | Subsection #1
 *      ----------------------------------------(end of page)
 *      data data data data data                                                                     | Subsection #2
 *      data data data data data                                                                     | Subsection #2
 *      data data data data data    <-- End of area to extract if(includeRegexAfterTable==false)     | Subsection #2
 *      B                           <-- End of area to extract if(includeRegexAfterTable==true)      | Subsection #2
 *      data data data data data
 *
 *
 *    In the above example, there is one matching area corresponding to the provided regex table delimiters
 *    which is composed of two subsections. The above search would be invoked as follows:
 *    RegexSearch("A",false,"B",false,docName,pageMargins), where pageMargins is a reference to a FilteredArea object
 *    corresponding to the header and footer margins of the document (this value can be initialized to null).
 *
 *    10/29/2017 REM; created.
 *    1/13/2018  REM; updated detectMatchingAreas to resolve pattern-detection bug
 *    1/27/2018  REM; added constructors to facilitate header/footer functionality as well as CLI work
 *    1/30/2018  REM; added static method skeleton for proof-of-concept header work, also added documentation
 *    2/4/2018   REM; added UpdatesOnResize so that the appropriate data can be passed back to the front-end
 *    4/14/2018  REM; updated documentation in file to reflect changes in last 2 months...
 *
 */


public class RegexSearch {

	/*
	 * This class is leveraged by convertPageSectionToString so that the TextElement ArrayList and the string
	 * associated with a given PDF page can both be returned to the caller
	 */

	/* checkSearchesFilterResize
	 *
	 * Determines which RegexSearch objects should be modified as a result of the user changing/defining a header filter
	 * for a page in the document
	 *
	 * @param file The PDDocument representation of the file
	 * @param currentRegexSearches The array of RegexSearch objects used on this document
	 * @param pageNumOfHeaderResize The number of the page upon which the header filter was resized
	 * @param pageHeight The height of the page containing the header filter resize event AS IT APPEARED IN THE GUI
	 * @param newHeaderHeight The new height of the header filter AS IT APPEARED IN THE GUI
	 * @param previousHeaderHeight The previous height of the header filter AS IT APPEARED IN THE GUI
	 * @return
	 */
	public static ArrayList<UpdatesOnResize> checkSearchesOnFilterResize( PDDocument file,
													         FilteredArea filterArea,
															 RegexSearch[] currentRegexSearches) {

		// System.out.println("In checkSearchesOnFilterResize:");

		// System.out.println("FilterArea height scale:" + filterArea.getHeaderHeightScale());
		// System.out.println("FilterArea footer scale:" + filterArea.getFooterHeightScale());


		ArrayList<UpdatesOnResize> updatedSearches = new ArrayList<>();
		for (RegexSearch regexSearch : currentRegexSearches){
			ArrayList<MatchingArea> areasToRemove = new ArrayList<
					MatchingArea>(regexSearch._matchingAreas);
			// System.out.println("Areas To Remove Length:"+areasToRemove.size());
			regexSearch._matchingAreas.clear();
			// System.out.println("Areas To Remove Length:"+areasToRemove.size());
			ArrayList<MatchingArea> areasToAdd = regexSearch._matchingAreas = regexSearch.detectMatchingAreas(file,filterArea);
			updatedSearches.add(new UpdatesOnResize(regexSearch,areasToAdd,areasToRemove,false));
		}
		return updatedSearches;

	}

	private static class UpdatesOnResize{
		RegexSearch updatedRegexSearch;         //The RegexSearch object that is being updated
		ArrayList<MatchingArea> areasAdded;     //New Areas discovered or areas modified upon resize event
		ArrayList<MatchingArea> areasRemoved;   //Previous Area dimensions for areas changed by resize event
		Boolean overlapsAnotherSearch; //Flag for when resize causes areas to be found overlapping a present query <--TODO: this variable is always false for now...need to update this once an overlap algorithm is written

		public UpdatesOnResize(RegexSearch regexSearch, ArrayList<MatchingArea> newAreasToAdd, ArrayList<MatchingArea> oldAreasToRemove,
							   Boolean resizeEventCausedAnOverlap){
			updatedRegexSearch = regexSearch;
			areasAdded = newAreasToAdd;
			areasRemoved = oldAreasToRemove;
			overlapsAnotherSearch = resizeEventCausedAnOverlap;
		}
	}

	private static final Integer INIT=0;

    //The regular expression denoting the beginning of the area(s) the user is interested in extracting. Given in constructor
	private Pattern _regexBeforeTable;
	//The regular expression denoting the end of the area(s) the user is interested in extracting. Given in constructor
	private Pattern _regexAfterTable;

	//A listing of all areas within the document that correspond to the provided regex search.
	private ArrayList<MatchingArea> _matchingAreas;
	
	private Boolean _includeRegexBeforeTable;
	private Boolean _includeRegexAfterTable;



	/*
	 * This constructor is designed to be used for parameters originating in JSON and where no header areas are defined
	 * NOTE: This constructor will soon be deprecated!!
	 * @param regexBeforeTable The text pattern that occurs in the document directly before the table that is to be extracted
	 * @param includeRegexBeforeTable Flag used to include the text pattern in the before the tabular data in the extraction zone
	 * @param regexAfterTable The text pattern that occurs in the document directly after the table that is to be extracted
	 * @param includeRegexAfterTable Flag used to include the text pattern directly after the tabular data in the extraction zone
	 * @param PDDocument The PDFBox model of the PDF document uploaded by the user.
	 * @param FilteredArea The dimensions of the header and footer margins for the document-can be initialized to null
	 */

	public RegexSearch(String regexBeforeTable, String includeRegexBeforeTable, String regexAfterTable,
					   String includeRegexAfterTable, PDDocument document, FilteredArea areaToFilter) {



		this(regexBeforeTable,Boolean.valueOf(includeRegexBeforeTable),regexAfterTable,
			Boolean.valueOf(includeRegexAfterTable),document,areaToFilter);

		// System.out.println(includeRegexBeforeTable);
		// System.out.println(includeRegexAfterTable);
		// System.out.println(Boolean.valueOf(includeRegexBeforeTable));
		// System.out.println(Boolean.valueOf(includeRegexAfterTable));
	}

	public RegexSearch(String regexBeforeTable,Boolean includeRegexBeforeTable, String regexAfterTable,
					   Boolean includeRegexAfterTable, PDDocument document, FilteredArea filterArea) {
		_regexBeforeTable = Pattern.compile(regexBeforeTable);
		_regexAfterTable = Pattern.compile(regexAfterTable);

		_includeRegexBeforeTable = includeRegexBeforeTable;
		_includeRegexAfterTable = includeRegexAfterTable;

		_matchingAreas = detectMatchingAreas(document, filterArea);


	}

	/* getRegexBeforeTable: basic getter function
	 * @return The regex pattern used to delimit the beginning of the table
	 */
	public String getRegexBeforeTable(){
		return _regexBeforeTable.toString();
	}
	/* getRegexAfterTable: basic getter function
	 * @return The regex pattern used to delimit the end of the table
	 */
	public String getRegexAfterTable(){
		return _regexAfterTable.toString();
	}


	private class SubSectionOfMatch {
		private Integer _pageNum; //Number of the page that the SubSectionOfMatch is drawn on
		private Rectangle _area; //Rectangular coordinates defining the boundaries of SubSectionOfMatch

		public SubSectionOfMatch(Integer pageNum, Rectangle area){
			_pageNum = pageNum;
			_area = area;
		}

		public Rectangle getArea() {return _area;}
		public Integer getHeight() { return Math.round(_area.height);}
		public Integer getTop()    { return Math.round(_area.getTop());}
		public Integer getLeft()   { return Math.round(_area.getLeft());}
		public Integer getWidth()  { return Math.round(_area.width);}
	}



    /*
     * MatchingArea on a per-page basis the areas (plural) of the PDF document that fall between text matching the
     * user-provided regex (this allows for tables that span multiple pages to be considered a single entity).
     * The key is the page number that the areas first begin. The LinkedList of Rectangles allows for multiple
     * areas to be associated with a given match (as in the case of multiple pages)
     */
	private static class MatchingArea extends HashMap<Integer,LinkedList<SubSectionOfMatch>> {

		private Integer _startPageNum;
		private Integer _endPageNum;


		public MatchingArea(Integer startPageNum, Integer endPageNum){
			_startPageNum = startPageNum;
			_endPageNum = endPageNum;
		}
	}

	/*
	 * @param pageNumber The one-based index into the document
	 * @return ArrayList<Rectangle> The values stored in _matchingAreas for a given page	
	 */
	public ArrayList<Rectangle> getSubSectionsForPage(Integer pageNumber){

		ArrayList<Rectangle> allMatchingAreas = new ArrayList<>();

		for( MatchingArea matchingArea : _matchingAreas) {
			for( int currentPageNumber : matchingArea.keySet()){
				if(currentPageNumber == pageNumber){
					for(SubSectionOfMatch subSectionOfMatch : matchingArea.get(currentPageNumber)){
						allMatchingAreas.add(subSectionOfMatch.getArea());
					}
				}
			}
		}

		return allMatchingAreas;
	}

	public ArrayList<Rectangle> getSubSectionsForPage(Integer pageNumber, BufferedWriter loggingBufferedWriter){

		ArrayList<Rectangle> allMatchingAreas = new ArrayList<>();

		for( MatchingArea matchingArea : _matchingAreas) {
			for( int currentPageNumber : matchingArea.keySet()){
				if(currentPageNumber == pageNumber){
					for(SubSectionOfMatch subSectionOfMatch : matchingArea.get(currentPageNumber)){
						allMatchingAreas.add(subSectionOfMatch.getArea());
					}
				}
			}

			// Logging - Match Found Notification Added to Log File
			try {
				if (matchingArea._startPageNum == matchingArea._endPageNum)
					loggingBufferedWriter.write("\tMatch Found - page #" + matchingArea._startPageNum);
				else
					loggingBufferedWriter.write("\tMatch Found - pages #" + matchingArea._startPageNum + "-" + matchingArea._endPageNum);
				loggingBufferedWriter.newLine();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}

		return allMatchingAreas;
	}


    public ArrayList<Rectangle> getAllSubSectionsOfMatches(){

        ArrayList<SubSectionOfMatch> allPagesMatchData = new ArrayList<>();
        ArrayList<Rectangle> allPagesSubSections = new ArrayList<>();

        for(MatchingArea matchingArea : _matchingAreas){
            for( int i : matchingArea.keySet()){
				allPagesMatchData.addAll(matchingArea.get(i));
			}
        }

        for(SubSectionOfMatch matchData : allPagesMatchData){
        	allPagesSubSections.add(matchData.getArea());
		}

        return allPagesSubSections;
    }
	
    /*
     * Inner class to retain information about a potential matching area while
     * iterating over the document and performing calculations to determine the rectangular 
     * area coordinates for matching areas. This may be overkill...
     */
	private final class DetectionData{
		DetectionData(){
			_pageBeginMatch = new AtomicInteger(INIT);
			_pageEndMatch = new AtomicInteger(INIT);
			_pageBeginCoord = new Point2D.Float();
			_pageEndCoord= new Point2D.Float();
		}
		
		AtomicInteger       _pageBeginMatch;
		AtomicInteger       _pageEndMatch;
		Point2D.Float       _pageBeginCoord;
		Point2D.Float       _pageEndCoord;

	}

	static final class SignOfOffset{
		private static final double POSITIVE_NO_BUFFER = 1;
        private static final double POSITIVE_WITH_BUFFER = 1.5;
        private static final double NEGATIVE_BUFFER = -.5;
        private static final int NONE = 0;
	};


	public static class FilteredArea {

		private Float scaleOfHeaderHeight;
		private Float scaleOfFooterHeight;

		public FilteredArea(Float headerHeightRatio, Float footerHeightRatio) {
			// System.out.println("Height of header:"+headerHeightRatio.toString());
			// System.out.println(("Height of footer:"+footerHeightRatio.toString()));
			scaleOfHeaderHeight = headerHeightRatio;
			scaleOfFooterHeight = footerHeightRatio;
		}


		public Float getHeaderHeightScale() {
			return scaleOfHeaderHeight;
		}

		public Float getFooterHeightScale() {
			return scaleOfFooterHeight;
		}

	}


	/*
	 * detectMatchingAreas: Detects the subsections of the document occurring 
	 *                      between the user-specified regexes. 
	 * 
	 * @param document The name of the document for which regex has been applied
	 * @param areasToFilter The header and footer sections of the document that are to be ignored.
	 * @return ArrayList<MatchingArea> A list of the sections of the document that occur between text 
	 * that matches the user-provided regex
	 */

	private ArrayList<MatchingArea> detectMatchingAreas(PDDocument document, FilteredArea areaToFilter) {

		ObjectExtractor oe = new ObjectExtractor(document);

		Integer totalNumPages = document.getNumberOfPages();
		LinkedList<DetectionData> potentialMatches = new LinkedList<>();
		potentialMatches.add(new DetectionData());

		for (Integer currentPage = 1; currentPage <= totalNumPages; currentPage++) {
			/*
			 * Convert PDF page to text
			 */
			Page page = oe.extract(currentPage);

			Integer top = (int) page.getTextBounds().getTop();

			if (areaToFilter != null) {
				if(areaToFilter.getHeaderHeightScale()>0){
					top = Math.round(areaToFilter.getHeaderHeightScale() * page.height);
				}
			}



			Integer height = Math.round(page.height);
			if (areaToFilter != null && areaToFilter.getFooterHeightScale()>0) {
					height = Math.round(page.height - areaToFilter.getFooterHeightScale() * page.height);
			}
			else{
					height = Math.round(height - (page.height-page.getTextBounds().getBottom()));
			}


			height -= top;

			ArrayList<TextElement> pageTextElements = (ArrayList<TextElement>) page.getText(
					new Rectangle(top, 0, page.width, height));

			StringBuilder pageAsText = new StringBuilder();

			for (TextElement element : pageTextElements) {
				pageAsText.append(element.getText());
			}

			// System.out.println("Area to parse as string:");
			// System.out.println(pageAsText.toString());

			/*
			 * Find each table on each page + tables which span multiple pages
			 */

			Integer startMatchingAt = 0;
			Matcher beforeTableMatches = _regexBeforeTable.matcher(pageAsText);
			Matcher afterTableMatches = _regexAfterTable.matcher(pageAsText);

			while (beforeTableMatches.find(startMatchingAt) || afterTableMatches.find(startMatchingAt)) {

				DetectionData tableUnderDetection;
				DetectionData lastTableUnderDetection = potentialMatches.getLast();

				if ((lastTableUnderDetection._pageBeginMatch.get() == INIT) || (lastTableUnderDetection._pageEndMatch.get() == INIT)) {
					tableUnderDetection = lastTableUnderDetection;
				} else if (lastTableUnderDetection._pageEndMatch.get() < lastTableUnderDetection._pageBeginMatch.get()) {
					tableUnderDetection = lastTableUnderDetection;
				} else if (lastTableUnderDetection._pageEndCoord.getY() < lastTableUnderDetection._pageBeginCoord.getY() &&
						(lastTableUnderDetection._pageEndMatch.get() == lastTableUnderDetection._pageBeginMatch.get())) {
					tableUnderDetection = lastTableUnderDetection;
				} else {
					tableUnderDetection = new DetectionData();
					potentialMatches.add(tableUnderDetection);
				}

				Integer beforeTableMatchLoc = (beforeTableMatches.find(startMatchingAt)) ? beforeTableMatches.start() : null;
				Integer afterTableMatchLoc = (afterTableMatches.find(startMatchingAt)) ? afterTableMatches.start() : null;

				Matcher firstMatchEncountered;
				double offsetScale;
				AtomicInteger pageToFind;
				Point2D.Float coordsToFind;

				Boolean bothMatchesEncountered = (beforeTableMatchLoc != null) && (afterTableMatchLoc != null);
				if (bothMatchesEncountered) {
					//
					// In the instance the Table Beginning Pattern and Table End Pattern both match a given text element,
					// the element chosen is dependent on what is currently in the tableUnderDetection
					//
					if (beforeTableMatchLoc.intValue() == afterTableMatchLoc.intValue()) {
						Boolean beginNotFoundYet = tableUnderDetection._pageBeginMatch.get() == INIT;
						firstMatchEncountered = (beginNotFoundYet) ? beforeTableMatches : afterTableMatches;

						//    --------------------------------
						//    Table Beginning  <------ |Offset
						//      Content                          (To include beginning, negative offset added: coords on top-left but buffer is needed)
						//      Content
						//      Content                         (To include end, positive offset added)
						//    Table End        <------ |Offset
						//    --------------------------------

						offsetScale = (beginNotFoundYet) ?
								//Negative offset for inclusion     Positive offset for exclusion
								((_includeRegexBeforeTable) ? SignOfOffset.NEGATIVE_BUFFER : SignOfOffset.POSITIVE_NO_BUFFER) :
								//Positive offset for inclusion    No offset for exclusion
								((_includeRegexAfterTable) ? SignOfOffset.POSITIVE_WITH_BUFFER : SignOfOffset.NONE);
						pageToFind = (beginNotFoundYet) ? tableUnderDetection._pageBeginMatch : tableUnderDetection._pageEndMatch;
						coordsToFind = (beginNotFoundYet) ? tableUnderDetection._pageBeginCoord : tableUnderDetection._pageEndCoord;

					} else {

						Boolean beginLocFoundFirst = beforeTableMatchLoc < afterTableMatchLoc;
						firstMatchEncountered = (beginLocFoundFirst) ? beforeTableMatches : afterTableMatches;
						offsetScale = (beginLocFoundFirst) ?
								((_includeRegexBeforeTable) ? SignOfOffset.NEGATIVE_BUFFER : SignOfOffset.POSITIVE_NO_BUFFER) :
								((_includeRegexAfterTable) ? SignOfOffset.POSITIVE_WITH_BUFFER : SignOfOffset.NONE);
						pageToFind = (beginLocFoundFirst) ? tableUnderDetection._pageBeginMatch : tableUnderDetection._pageEndMatch;
						coordsToFind = (beginLocFoundFirst) ? tableUnderDetection._pageBeginCoord : tableUnderDetection._pageEndCoord;
					}
				} else {
					Boolean beginLocNotFound = (beforeTableMatchLoc == null);
					firstMatchEncountered = (beginLocNotFound) ? afterTableMatches : beforeTableMatches;
					offsetScale = (beginLocNotFound) ?
							((_includeRegexAfterTable) ? SignOfOffset.POSITIVE_WITH_BUFFER : SignOfOffset.NONE) :
							((_includeRegexBeforeTable) ? SignOfOffset.NEGATIVE_BUFFER : SignOfOffset.POSITIVE_NO_BUFFER);
					pageToFind = (beginLocNotFound) ? tableUnderDetection._pageEndMatch : tableUnderDetection._pageBeginMatch;
					coordsToFind = (beginLocNotFound) ? tableUnderDetection._pageEndCoord : tableUnderDetection._pageBeginCoord;
				}

				Integer firstMatchIndex = firstMatchEncountered.start();

				Float xCoordinate = pageTextElements.get(firstMatchIndex).x;
				Float yCoordinate = pageTextElements.get(firstMatchIndex).y;
				Float offset = pageTextElements.get(firstMatchIndex).height;
				yCoordinate += (float) (offset * offsetScale);

				coordsToFind.setLocation(xCoordinate, yCoordinate);
				pageToFind.set(currentPage);
				startMatchingAt = firstMatchEncountered.end();

			}
		}

		/*
		 * Remove the last potential match if its data is incomplete
		 */
		DetectionData lastPotMatch = potentialMatches.getLast();

		if ((lastPotMatch._pageBeginMatch.get() == INIT) || (lastPotMatch._pageEndMatch.get() == INIT)) {
			potentialMatches.removeLast();
		} else if ((lastPotMatch._pageEndMatch.get() < lastPotMatch._pageBeginMatch.get())) {
			potentialMatches.removeLast();
		} else if ((lastPotMatch._pageEndMatch.get() == lastPotMatch._pageBeginMatch.get()) &&
				(lastPotMatch._pageEndCoord.getY() < lastPotMatch._pageBeginCoord.getY())) {
			potentialMatches.removeLast();
		}

		return calculateMatchingAreas(potentialMatches, document, areaToFilter);

	}

	/*
	 * calculateMatchingAreas: Determines the rectangular coordinates of the subsections of each
	 *                         matching area for the user-specified regex(_regexBeforeTable,_regexAfterTable)
	 * 
	 * @param foundMatches A list of DetectionData values
	 * @return ArrayList<MatchingArea> A Hashmap 
	 */
	private ArrayList<MatchingArea> calculateMatchingAreas(LinkedList<DetectionData> foundMatches, PDDocument document,
														   FilteredArea areaToFilter) {
		
		ArrayList<MatchingArea> matchingAreas = new ArrayList<>();
		
		ObjectExtractor oe = new ObjectExtractor(document);


		while(!foundMatches.isEmpty()) {

			DetectionData foundTable = foundMatches.pop();

            if(foundTable._pageBeginMatch.get() == foundTable._pageEndMatch.get()) {
            
            	float width = oe.extract(foundTable._pageBeginMatch.get()).width;
            	float height = foundTable._pageEndCoord.y-foundTable._pageBeginCoord.y;
            	
            	LinkedList<SubSectionOfMatch> matchSubSections = new LinkedList<>();
            	matchSubSections.add(new SubSectionOfMatch(foundTable._pageBeginMatch.get(),
						new Rectangle(foundTable._pageBeginCoord.y,0,width,height)));
            	
            	MatchingArea matchingArea = new MatchingArea(foundTable._pageBeginMatch.get(), foundTable._pageEndMatch.get());
            	matchingArea.put(foundTable._pageBeginMatch.get(), matchSubSections);
            
            	matchingAreas.add(matchingArea);
            
			}
            else {
            	
            	MatchingArea matchingArea = new MatchingArea(foundTable._pageBeginMatch.get(),foundTable._pageEndMatch.get());
            	/*
            	 * Create sub-area for table from directly below the pattern-before-table content to the end of the page
            	 */
            	Page currentPage =  oe.extract(foundTable._pageBeginMatch.get());
            	LinkedList<SubSectionOfMatch> tableSubArea = new LinkedList<>();

            	Float footer_height = (areaToFilter==null) ? (float)0:
						areaToFilter.getFooterHeightScale()*currentPage.height;

            	Float height = currentPage.height-foundTable._pageBeginCoord.y;

            	if(footer_height>0){
            		height-=footer_height;
				}
				else{
            		height= height - (currentPage.height-currentPage.getTextBounds().getBottom());
				}

            	tableSubArea.add( new SubSectionOfMatch(currentPage.getPageNumber(), new Rectangle(foundTable._pageBeginCoord.y,0,currentPage.width,
            			                        height))); //Note: limitation of this approach is that the entire width of the page is used...could be problematic for multi-column data
            	matchingArea.put(currentPage.getPageNumber(), tableSubArea);
            	
            	/*
            	 * Create sub-areas for table that span the entire page
            	 */

            	for (Integer iter=currentPage.getPageNumber()+1; iter<foundTable._pageEndMatch.get(); iter++) {
            		currentPage = oe.extract(iter);

            		Integer subAreaTop;

            		if((areaToFilter!=null) && (areaToFilter.getHeaderHeightScale()>0)){
            			subAreaTop= Math.round(areaToFilter.getHeaderHeightScale()*currentPage.height);
					}
            		else{
						subAreaTop= (int)Math.round(0.5*(currentPage.getTextBounds().getTop()));
					}

            		System.out.println("Current Page: "+ currentPage.getPageNumber());
            		System.out.println("SubAreaTop: "+ subAreaTop);

            		Float subAreaHeight = currentPage.height-subAreaTop;

					// System.out.println("Sub Area Height Before Subtraction:" + subAreaHeight);

            		if(areaToFilter!=null && areaToFilter.getFooterHeightScale()>0){
						subAreaHeight -=areaToFilter.getFooterHeightScale()*currentPage.height;
					}
					else{
						// System.out.println("Last Page: " + foundTable._pageEndMatch.get());
						// System.out.println("Page #: "+currentPage.getPageNumber());
						// System.out.println("area to filter == null");
						// System.out.println("Current Page Height: "+ currentPage.height);
						// System.out.println("Bottom Text Bounds of Current Page: " + currentPage.getTextBounds().getBottom());
						// System.out.println("Current Height - Bottom Text Bound"+ (currentPage.height- currentPage.getTextBounds().getBottom()));

            			subAreaHeight -= (float)(0.5)*(currentPage.height - (currentPage.getTextBounds().getBottom()));

					}


					// System.out.println("Sub Area Height: "+subAreaHeight);
					// System.out.println("Sub Area Top: " +subAreaTop);

            		tableSubArea = new LinkedList<>();

            		tableSubArea.add(new SubSectionOfMatch(currentPage.getPageNumber(),
							new Rectangle(subAreaTop,0,currentPage.width,
									subAreaHeight)));
            		matchingArea.put(currentPage.getPageNumber(), tableSubArea);
            	}
                
            	/*
            	 * Create sub-areas for table from the top of the page to directly before the pattern-after-table content 
            	 */
            	
            	currentPage = oe.extract(foundTable._pageEndMatch.get());
                tableSubArea = new LinkedList<>();

				Integer top = (areaToFilter!=null) ? Math.round(areaToFilter.getHeaderHeightScale()*currentPage.height) :
						(int) Math.round(0.5*(currentPage.getTextBounds().getMinY()));

				// System.out.println("Current Page #:"+currentPage.getPageNumber());
				// System.out.println("Top:"+top);
                tableSubArea.add(new SubSectionOfMatch(currentPage.getPageNumber(), new Rectangle(top,0,currentPage.width,foundTable._pageEndCoord.y-top)));

                matchingArea.put(currentPage.getPageNumber(), tableSubArea);
                matchingAreas.add(matchingArea);
            }
		}

		return matchingAreas;
	}
}
