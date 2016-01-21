package whynot.test.pack;

import generation.api.pack.TriplesGeneration;
import generation.api.pack.WhyNotTriples;
import jena.api.pack.JenaAPI;
import lucene.api.pack.LuceneAPI;
import match.api.pack.TriplesMatch;
import match.api.pack.UserQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.*;

import config.api.pack.Config;

public class Test {	

	//private Config config = new Config();
	//�־û�TDB��λ��
	private String datasetpath = "D:/workspace/WhyNot/tdb_type_prop";	//tdb_info2m
	//�����ĵ�ַ
	private String indexpath = "D:/workspace/WhyNot/index_label";	//index_info2m
	//��ȡ�����ļ���·��
	private String filePath = "D:/Ontology/infobox_properties_en.nt";
	
	//offline process, create TDB and build index
	public void preProcess() {
		//1.����һ��TDB���ݼ�
		
		JenaAPI jena = new JenaAPI(datasetpath);	//ʵ����JenaAPI��
		jena.CreateTDB(filePath);
		//ִ���û���ѯ
		//jena.QueryTDB(sparql);
		
		//2.��������
		LuceneAPI lucene = new LuceneAPI(indexpath);	//ʵ����luncene�ӿ�
		HashMap<Resource, List<RDFNode>> soMap = jena.ListStatement();		//subject and object(Literal @en) pair form the instance file
		HashMap<Resource, List<RDFNode>> classPairMap = jena.ListStatement("http://www.w3.org/2000/01/rdf-schema#label");	//subject and object pair from the ontology file
		for(Resource key : classPairMap.keySet()) {
			List<RDFNode> value = classPairMap.get(key);
			if(soMap.containsKey(key)) {
				List<RDFNode> valueList = soMap.get(key);
				for(RDFNode node : value) {
					valueList.add(node);
				}
			}
			else {
				soMap.put(key, value);	//combine the classPairMap to soMap
			}
		}
		lucene.BuildIndex(soMap);		//build index of the selected subject-object pair			
	}
	
	public Map<Statement, String> computeExplanation(String keyword, String query, Map<Statement, String> map) {
		long startTime = System.currentTimeMillis();
		
		//3.�����û���ѯ�õ���ѯ����Ԫ��ļ���
		UserQuery userQuery = new UserQuery();
		ArrayList<Statement> queryTriples = userQuery.triplesStorage(query);
		ArrayList<String> variables = userQuery.variablesExtract(query);
		System.out.println("\nThe triples in user query are :");
		printStatement(queryTriples);
		System.out.println("\nThe variables in query are :");
		printString(variables);
		
		for(int varNo = 0; varNo < variables.size(); varNo++) {
			//4.��object���¸���keyword������Ӧ�����uri��������ȡ����������ֱ����ص���Ԫ��
			WhyNotTriples whynotTriples = new WhyNotTriples();
			List<String> uris = whynotTriples.ObtainSubUri(keyword);
			System.out.println("\nCorresponding subjects of the keyword: (" + uris.size() + " uris)");
			printString(uris);
			List<Map<Statement, String>> mapList = new ArrayList<Map<Statement, String>>();
			for(String uri : uris) {
				int count = 0;
				System.out.println(++count + ": " + uri);
				ArrayList<Statement> questionTriples = whynotTriples.GenerateTriples(uri);
				System.out.println("\nRelated triples of the uri:");
				printStatement(questionTriples);
				
/*				//5.����������ص�������Ԫ����з���
				TriplesGeneration triplesGeneration = new TriplesGeneration();
				ArrayList<Statement> generalizedTriples = triplesGeneration.generalizeTriplesWithPellet(questionTriples); 	
				System.out.println("\nAfter Generation, the triples are :");
				printStatement(generalizedTriples);		
*/				
				//6.���ζԲ�ѯ��Ԫ�鼯���е�ÿһ����Ԫ���ڷ�����Ԫ�鼯���н���ƥ�䣬�õ������Ƶ���Ԫ��
				TriplesMatch triplesMatch = new TriplesMatch();
				System.out.println("\nMatching...");
				Map<Statement, String> matchMap = triplesMatch.match(queryTriples, questionTriples);	//questionTriples
				mapList.add(matchMap);
			}
			float maxScore = 0f;
			int flag = 0;
			//find the matched map with the maximum global similarity score (disambiguation)
			for(int i = 0; i < mapList.size(); i++) {	
				Map<Statement, String> uriMap = mapList.get(i);		//each uri corresponds to a map which store the query triple and its most similar triple in generalized triples collection and score
				float score = 0f;
				for(Statement stmt : uriMap.keySet()) {
					score += Float.parseFloat(uriMap.get(stmt).split("\t")[1]);		//the second part is the similarity score of key and value(both are triples)
				}
				if(score > maxScore) {
					maxScore = score;
					flag = i;
				}
			}
			Map<Statement, String> matchestMap = mapList.get(flag);
			//����ȫ�ֵ�map
			for(Statement stmt : matchestMap.keySet()) {
				float newScore = Float.parseFloat(matchestMap.get(stmt).split("\t")[1]);
				if(map.containsKey(stmt)) {
					float oldScore = Float.parseFloat(map.get(stmt).split("\t")[1]);
					if(newScore > oldScore) {
						map.remove(stmt, map.get(stmt));
						map.put(stmt, matchestMap.get(stmt));
					}
				}
				else {
					map.put(stmt, matchestMap.get(stmt));
				}
			}
			//System.out.println("\nUpdated Map :");
			//printMap(map);
			//uris.clear();
			List<String> otherVarsInstance = new ArrayList<String>();
			for(Statement stmt : queryTriples) {
				if(stmt.getSubject().toString().equals(variables.get(varNo)) && stmt.getObject().toString().startsWith("?")) {
					String triple = map.get(stmt).split("\t")[0];
					Resource subject = ResourceFactory.createResource(triple.substring(1, triple.indexOf(",")));
					String sparql = "select * where { <" + subject + "> <" + stmt.getPredicate() + "> ?obj .}";
					System.out.println(sparql);
					JenaAPI jena = new JenaAPI(datasetpath);
					ResultSet rs = jena.QueryTDB(sparql);
					while(rs.hasNext()) {
						QuerySolution qs = rs.nextSolution();
						RDFNode node = qs.get("?obj");
						if(node.isLiteral()) {
							continue;
						}
						//uris.add(node.toString());
						System.out.println(node);
						otherVarsInstance.add(node.toString());
					}
					break;
				}
			}			
			//varNo++;
			for(int i = 0; i < otherVarsInstance.size(); i++) {
				String instance = otherVarsInstance.get(i);
				keyword = instance.toString().substring(instance.toString().indexOf("resource") + 9, instance.toString().length()).replace("_", " ");
				//computeExplanation(keyword, query, map);
			}
			System.out.println("\nAfter matching, the most similar triples pair are :");
			printMap(map);
		}
		//7.�������Ƶ���Ԫ����к�ȡ��������µĲ�ѯ
		System.out.println("\nMatching Results:");
		printMap(map);
		long endTime = System.currentTimeMillis();
		System.out.println("\nTime : " + (endTime - startTime) + "ms");		
		return map;
	}
	
	public static void main(String[] args) {
		//�û������еĹؼ���
		String[] keywords = { "Aristotle", "ANSI", "Anaximander", "George Washington", "Bayerische Motoren Werke AG", "Alan Turing", "Akira Kurosawa", "Averroes", "Alain Connes", "Aristotle", "Asterales", "Andrew Johnson", "On the Road" };	//James Buchanan
		//�û���ѯ
		String[] sparql = { "PREFIX dbpedia-owl: <http://dbpedia.org/resource/> SELECT * WHERE { ?p <http://dbpedia.org/property/mainInterests> dbpedia-owl:Natural_philosophy .}",
							"PREFIX dbpr: <http://dbpedia.org/resource/> PREFIX dbpp: <http://dbpedia.org/property/> SELECT ?var WHERE { ?var dbpp:headquarters dbpr:New_York_City . }",
							"PREFIX dbpr: <http://dbpedia.org/resource/> PREFIX dbpp: <http://dbpedia.org/property/> SELECT ?var WHERE { ?var dbpp:influences dbpr:Arthur_Schopenhauer . }",
							"PREFIX dbpr: <http://dbpedia.org/resource/> PREFIX dbpp: <http://dbpedia.org/property/> SELECT ?var WHERE { ?var dbpp:office \"President of the United States\"@en . }",
							"PREFIX dbpr: <http://dbpedia.org/resource/> PREFIX dbpp: <http://dbpedia.org/property/> SELECT ?var WHERE { ?var  <http://dbpedia.org/property/products> \"Luxury cars\"@en . \n ?var <http://dbpedia.org/property/locationCountry> \"Germany\"@en . }",
							"PREFIX dbpr: <http://dbpedia.org/resource/> PREFIX dbpp: <http://dbpedia.org/property/> SELECT ?var WHERE { ?var dbpp:occupation dbpr:Engineer.	?var dbpp:occupation dbpr:Mathematician.}",
							"PREFIX dbpp: <http://dbpedia.org/property/> PREFIX dbpr: <http://dbpedia.org/resource/> SELECT ?var WHERE { ?var dbpp:occupation dbpr:Film_director.	?var dbpp:awards dbpr:Elliott_Cresson_Medal.}",
							"PREFIX dbpp: <http://dbpedia.org/property/> PREFIX dbpr: <http://dbpedia.org/resource/> SELECT * WHERE {	?var1 dbpp:era dbpr:Medieval_philosophy .	?var1 dbpp:influences ?var2 .	?var2 dbpp:name \"Avicenna\"@en .}",
							"SELECT * WHERE { 	?var <http://dbpedia.org/property/nationality> \"French\"@en .	?var <http://dbpedia.org/property/workplaces> <http://dbpedia.org/resource/Institut_des_Hautes_%C3%89tudes_Scientifiques> .	?var <http://dbpedia.org/property/fields> <http://dbpedia.org/resource/Mathematics> }",
							"PREFIX dbpp: <http://dbpedia.org/property/> PREFIX dbpr: <http://dbpedia.org/resource/> SELECT * WHERE { 	?var dbpp:nationality dbpr:Greeks .	?var dbpp:era dbpr:Ancient_philosophy .	?var dbpp:region dbpr:Western_philosophy . ?var dbpp:mainInterests dbpr:Ethics}",
							"PREFIX dbpp: <http://dbpedia.org/property/> SELECT * WHERE { 	?var dbpp:ordo \"Asterales\"@en .	?var dbpp:authority ?var2.}",
							"PREFIX dbpp: <http://dbpedia.org/property/> PREFIX dbpr: <http://dbpedia.org/resource/> SELECT * WHERE { 	?var1 dbpp:war dbpr:John_Schofield .	?var1 dbpp:appointer ?var2.	?var2 dbpp:name \"Lincoln\"@en .}",
							"PREFIX dbo: <http://dbpedia.org/ontology/> PREFIX res: <http://dbpedia.org/resource/> PREFIX prop: <http://dbpedia.org/property/> PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> SELECT DISTINCT ?uri WHERE {      ?uri rdf:type dbo:Book .        ?uri prop:publisher res:Viking_Press .        ?uri prop:author res:Jack_Kerouac .}"
		};
		Test test = new Test();
		//test.preProcess();	//Phrase 1 : create TDB dataset and lucene index
		Map<Statement, String> map = new HashMap<Statement, String>();
		test.computeExplanation(keywords[0], sparql[0], map);
		//System.out.println("\nThe Final Global Map is :");
		//printMap(map);
		
		for(int i = 0; i < keywords.length; i++) {
			//test.computeExplanation(keywords[i], sparql[i]);
		}
	}
	
	public static void printString(List<String> strList) {
		for(String str : strList) {
			System.out.println(str);
		}
	}
	
	public static void printStatement(ArrayList<Statement> stmtArray) {
		for(Statement stmt : stmtArray) {
			System.out.println(stmt);
		}
	}
	
	public static void printMap(Map<Statement, String> map) {
		Iterator<Statement> it = map.keySet().iterator();
		while(it.hasNext()) {
			Statement key = (Statement)it.next();
			String value = map.get(key);
			System.out.println(key + "\t" + value);
		}
	}
	
	public static void printIndexMap(HashMap<Resource, RDFNode> map) {
		for(Resource res : map.keySet()) {
			RDFNode node = map.get(res);
			System.out.println(res + "\t" + node);
		}
	}
	
}
