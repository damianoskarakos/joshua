/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.decoder.ff.lm.distributed_lm;

import joshua.corpus.SymbolTable;
import joshua.decoder.BuildinSymbol;
import joshua.decoder.SrilmSymbol;
import joshua.decoder.ff.lm.NGramLanguageModel;
import joshua.decoder.ff.lm.buildin_lm.LMGrammarJAVA;
import joshua.decoder.ff.lm.srilm.LMGrammarSRILM;
import joshua.util.io.LineReader;
import joshua.util.Regex;

import java.io.IOException;
//import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
//import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 * this class implement 
 * (1) load lm file
 * (2) listen to connection request
 * (3) serve request for LM probablity
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
public class LMServer {
	
	private static final Logger logger = Logger.getLogger(LMServer.class.getName());
	
	//common options
	public static int port = 9800;
	static boolean use_srilm = true;
	public static boolean use_left_euqivalent_state = false;
	public static boolean use_right_euqivalent_state = false;
	static int g_lm_order = 3;
	static double lm_ceiling_cost = 100;//TODO: make sure LMGrammar is using this number
	static String remote_symbol_tbl = null;
	
	//lm specific
	static String lm_file              = null;
	static Double interpolation_weight = null;//the interpolation weight of this lm
	static String g_host_name          = null;
	
	//pointer
	static NGramLanguageModel p_lm;
	static HashMap<String,String> request_cache = new HashMap<String,String>();//cmd with result
	static int cache_size_limit = 3000000;
	
	//	stat
	static int g_n_request   = 0;
	static int g_n_cache_hit = 0;
	
	static SymbolTable p_symbolTable;
	
	
	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			System.err.println("wrong command, correct command should be: java LMServer config_file");
			
			if (logger.isLoggable(Level.FINE)) {
				logger.fine("num of args is "+ args.length);
				for (int i = 0; i < args.length; i++) {
					logger.fine("arg is: " + args[i]);
				}
			}
			
			System.exit(1);
		}
		String config_file = args[0].trim();
		read_config_file(config_file);
		
		ServerSocket serverSocket = null;
		LMServer server = new LMServer();
		
		//p_lm.write_vocab_map_srilm(remote_symbol_tbl);
		//####write host infomation
		//String hostname=LMServer.findHostName();//this one is not stable, sometimes throw exception
		//String hostname="unknown";
		
		
		//### begin loop
		try {
			serverSocket = new ServerSocket(port);
			if (null == serverSocket) {
				logger.severe("Error: server socket is null");
				System.exit(0);
			}
			init_lm_grammar();
			
			logger.info("finished lm reading, wait for connection");
			
			// serverSocket = new ServerSocket(0);//0 means any free port
			// port = serverSocket.getLocalPort();
			while (true) {
				Socket socket = serverSocket.accept();
				logger.info("accept a connection from client");
				ClientHandler handler = new ClientHandler(socket,server);
				handler.start();
			}
		} catch(IOException ioe) {
			logger.severe("cannot create serversocket at port or connection fail");
			ioe.printStackTrace();
		} finally {
			try {
				serverSocket.close();
			} catch(IOException ioe) {
				ioe.printStackTrace();
			}
		}
	}
	
	
	// BUG: duplicates initializeLanguageModel and initializeSymbolTable in JoshuaDecoder, needs unifying
	public static void init_lm_grammar() throws IOException {
		if (use_srilm) {
			if (use_left_euqivalent_state || use_right_euqivalent_state) {
				logger.severe("when using local srilm, we cannot use suffix stuff");
				System.exit(0);
			}
			p_symbolTable = new SrilmSymbol(remote_symbol_tbl, g_lm_order);
			p_lm = new LMGrammarSRILM((SrilmSymbol)p_symbolTable, g_lm_order, lm_file);
			
		} else {
			//p_lm = new LMGrammar_JAVA(g_lm_order, lm_file, use_left_euqivalent_state);
			//big bug: should load the consistent symbol files
			p_symbolTable = new BuildinSymbol(remote_symbol_tbl);
			p_lm = new LMGrammarJAVA((BuildinSymbol)p_symbolTable, g_lm_order, lm_file, use_left_euqivalent_state, use_right_euqivalent_state);
		}
	}
	
	
	
	// BUG: this is duplicating code in JoshuaConfiguration, needs unifying
	public static void read_config_file(String config_file)
	throws IOException {
		
		LineReader configReader = new LineReader(config_file);
		try { for (String line : configReader) {
			//line = line.trim().toLowerCase();
			line = line.trim();
			if (Regex.commentOrEmptyLine.matches(line)) continue;
			
			if (line.indexOf("=") != -1) { //parameters
				String[] fds = Regex.equalsWithSpaces.split(line);
				if (fds.length != 2) {
					logger.severe("Wrong config line: " + line);
					System.exit(0);
				}
				if ("lm_file".equals(fds[0])) {
					lm_file = fds[1].trim();
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("lm file: %s", lm_file));
					
				} else if ("use_srilm".equals(fds[0])) {
					use_srilm = Boolean.valueOf(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("use_srilm: %s", use_srilm));
					
				} else if ("lm_ceiling_cost".equals(fds[0])) {
					lm_ceiling_cost = Double.parseDouble(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("lm_ceiling_cost: %s", lm_ceiling_cost));
					
				} else if ("use_left_euqivalent_state".equals(fds[0])) {
					use_left_euqivalent_state = Boolean.valueOf(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("use_left_euqivalent_state: %s", use_left_euqivalent_state));
					
				} else if ("use_right_euqivalent_state".equals(fds[0])) {
					use_right_euqivalent_state = Boolean.valueOf(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("use_right_euqivalent_state: %s", use_right_euqivalent_state));
					
				} else if ("order".equals(fds[0])) {
					g_lm_order = Integer.parseInt(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("g_lm_order: %s", g_lm_order));
					
				} else if ("remote_lm_server_port".equals(fds[0])) {
					port = Integer.parseInt(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("remote_lm_server_port: %s", port));
					
				} else if ("remote_symbol_tbl".equals(fds[0])) {
					remote_symbol_tbl = fds[1];
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("remote_symbol_tbl: %s", remote_symbol_tbl));
					
				} else if ("hostname".equals(fds[0])) {
					g_host_name = fds[1].trim();
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("host name is: %s", g_host_name));
					
				} else if ("interpolation_weight".equals(fds[0])) {
					interpolation_weight = Double.parseDouble(fds[1]);
					if (logger.isLoggable(Level.FINE))
						logger.fine(String.format("interpolation_weightt: %s", interpolation_weight));
					
				} else {
					logger.warning("LMServer doesn't use config line: " + line);
					//System.exit(0);
				}
			}
		} } finally { configReader.close(); }
	}
	
	
	// used by server to process diffent Client
	public static class ClientHandler extends Thread {
		public class DecodedStructure {
			String cmd;
			int    num;
			int[]  wrds;
		}
		
		LMServer               parent;
		private Socket         socket;
		private BufferedReader in;
		private PrintWriter    out;
		
		
		public ClientHandler(Socket sock, LMServer pa) throws IOException {
			parent = pa;
			socket = sock;
			in = new BufferedReader(
				new InputStreamReader(socket.getInputStream()));
			out = new PrintWriter(
				new OutputStreamWriter(socket.getOutputStream()));
		}
		
		
		public void run() {
			String line_in;
			String line_out;
			try {
				while ((line_in = in.readLine()) != null) {
					//TODO block read
					//System.out.println("coming in: " + line);
					//line_out = process_request(line_in);
					line_out = process_request_no_cache(line_in);
					
					out.println(line_out);
					out.flush();
				}
			} catch(IOException ioe) {
				ioe.printStackTrace();
			} finally {
				try {
					in.close();
					out.close();
					socket.close();
				} catch(IOException ioe) {
					ioe.printStackTrace();
				}
			}
		}
		
		
		private String process_request_no_cache(String packet) {
			//search cache
			g_n_request++;
			String cmd_res = process_request_helper(packet);
			if (logger.isLoggable(Level.FINE) && g_n_request % 50000 == 0) {
				logger.fine("n_requests: " + g_n_request);
			}
			return cmd_res;
		}
		
		
		
		//This is the funciton that application specific
		private String process_request_helper(String line) {
			DecodedStructure ds = decode_packet(line);
			
			if ("prob".equals(ds.cmd)) {
				return get_prob(ds);
			} else if ("prob_bow".equals(ds.cmd)) {
				return get_prob_backoff_state(ds);
			} else if ("equiv_left".equals(ds.cmd)) {
				return get_left_equiv_state(ds);
			} else if ("equiv_right".equals(ds.cmd)) {
				return get_right_equiv_state(ds);
			} else {
				logger.severe("error : Wrong request line: " + line);
				//System.exit(1);
				return "";
			}
		}
		
		
		// format: prob order wrds
		private String get_prob(DecodedStructure ds) {
			return Double.toString(p_lm.ngramLogProbability(ds.wrds, ds.num));
		}
		
		
		// format: prob order wrds
		private String get_prob_backoff_state(DecodedStructure ds) {
			logger.severe("Error: call get_prob_backoff_state in lmserver, must exit");
			System.exit(1);
			return null;
			/*Double res = p_lm.get_prob_backoff_state(ds.wrds, ds.num, ds.num);
			return res.toString();*/
		}
		
		
		// format: prob order wrds
		private String get_left_equiv_state(DecodedStructure ds) {
			logger.severe("Error: call get_left_equiv_state in lmserver, must exit");
			System.exit(1);
			return null;
		}
		
		
		// format: prob order wrds
		private String get_right_equiv_state(DecodedStructure ds) {
			logger.severe("Error: call get_right_equiv_state in lmserver, must exit");
			System.exit(1);
			return null;
		}
		
		
		private DecodedStructure decode_packet(String packet) {
			String[] fds         = Regex.spaces.split(packet);
			DecodedStructure res = new DecodedStructure();
			res.cmd              = fds[0].trim();
			res.num              = Integer.parseInt(fds[1]);
			int[] wrds           = new int[fds.length-2];
			
			for (int i = 2; i < fds.length; i++) {
				wrds[i-2] = Integer.parseInt(fds[i]);
			}
			res.wrds = wrds;
			return res;
		}
	}
}
