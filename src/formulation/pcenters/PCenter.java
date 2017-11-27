package formulation.pcenters;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import cplex.Cplex;
import formulation.interfaces.IFNodeV;
import formulation.pcenters.pCenterCreator.PCRadCreator;
import formulation.pcenters.pCenterCreator.PCSCCreator;
import formulation.pcenters.pCenterCreator.PCSCOCreator;
import formulation.pcenters.pCenterCreator.PCenterCreator;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.UnknownObjectException;
import inequality_family.Range;
import pcenters.PCResult;
import variable.CplexVariableGetter;

public abstract class PCenter<CurrentParam extends PCenterParam> implements IFNodeV{

	/** Number of clients */
	public int N;

	/** Number of factories */
	public int M;

	/** Maximal number of built centers */
	public int p;

	/** Distance between the clients and the factories */
	public double[][] d;

	public IloNumVar[] y;

	protected CplexVariableGetter cvg;
	protected CurrentParam param;

	/* Best known lower bound after the initialization phase (i.e., before the problem is solved) */
	protected double lb = -Double.MAX_VALUE;

	/** Best known upper bound after the initialization phase (i.e., before the problem is solved)  */
	protected double ub = Double.MAX_VALUE;

	/** True if the first factory is dominated (only used for the first factory j as dominated factories are represented by setting d[0][j] = Double.MAX_VALUE but if d[0][0] is modified it also modify the first client) */
	private boolean isFirstClientDominated = false;

	/** True if the first client is dominated (only used for the first client j as dominated clients are represented by setting d[j][0] = Double.MAX_VALUE but if d[0][0] is modified it also modify the first factory) */
	private boolean isFirstFactoryDominated = false;


	/**
	 * Create a p-center formulation from an input file.
	 * The input file format is:
	 * - The first line contains 3 integers separated by a space, they respectively correspond to:
	 * 		- The number of client N
	 * 		- The number of factories M
	 * 		- The value of p (I guess...)
	 * - The N next lines contain M values such that the value on line i and column j is the distance between the client number i and the factory number j.
	 * These values must be separated by spaces
	 * @param inputFile
	 * @throws IOException 
	 * @throws InvalidPCenterInputFile 
	 */
	public PCenter(CurrentParam param) throws IOException, InvalidPCenterInputFile {

		InputStream ips=new FileInputStream(param.inputFile); 
		InputStreamReader ipsr=new InputStreamReader(ips);
		BufferedReader br=new BufferedReader(ipsr);

		/* Read the first line */
		String ligne = br.readLine();
		String[] sTemp = ligne.split(" ");

		if(sTemp.length < 3){
			br.close();
			ips.close();
			throw new InvalidPCenterInputFile(param.inputFile, "The first line contains less than three values.");
		}

		N = Integer.parseInt(sTemp[0]);
		M = Integer.parseInt(sTemp[1]);
		p = Integer.parseInt(sTemp[2]);

		d = new double[N][M];
		int clientNb = 1;

		/* Read the next lines */
		while ((ligne=br.readLine())!=null && clientNb <= M){
			sTemp = ligne.split(" ");

			if(sTemp.length < M){
				br.close();
				ips.close();
				throw new InvalidPCenterInputFile(param.inputFile, "Line n°" + clientNb + " contains less than " + M + " values separated by spaces.");
			}

			for(int j = 0 ; j < M ; j++){
				double cDouble = Double.parseDouble(sTemp[j]);
				d[clientNb - 1][j] = cDouble;
			}

			clientNb++;			  

		}

		br.close();
		ips.close();

		if(clientNb - 1 < M)
			throw new InvalidPCenterInputFile(param.inputFile, "The file only contains " + (clientNb-1) + " distances lines instead of " + M);

		this.param = param;

		double previousLB = Double.MAX_VALUE;
		double previousUB = -Double.MAX_VALUE;

		lb = param.initialLB;
		ub = param.initialUB;

		if(lb != -Double.MAX_VALUE)
			for(int i = 0; i < N; ++i)
				for(int j = 0; j < M; ++j)
					d[i][j] = Math.max(d[i][j], lb);

		if(ub != Double.MAX_VALUE)
			for(int i = 0; i < N; ++i)
				for(int j = 0; j < M; ++j)
					d[i][j] = Math.min(d[i][j], ub + 1);


//		System.out.print(" (b1: " + lb + "/" + ub + ") ");
		
		int iteration = 0;
		boolean boundImproved = Math.abs(ub - lb) > 1E-4;

		while(boundImproved
				&& (param.computeBoundsSeveralTimes || iteration == 0)
				&& iteration < 10){

			previousLB = lb;
			previousUB = ub;

			if(param.useLB0 || param.useLB1) {
				lb = computeLowerBound();
				lb = Math.max(previousLB, lb);

				if(lb != previousLB)
					for(int i = 0; i < N; ++i)
						for(int j = 0; j < M; ++j)
							d[i][j] = Math.max(d[i][j], lb);
				//				if(d[i][j] < lb)
				//					d[i][j] = 0;
			}

			if(param.useUB0 || param.useUB1) {
				ub = computeUpperBound();
				ub = Math.min(previousUB, ub);

				if(ub != previousUB)
				for(int i = 0; i < N; ++i)
					for(int j = 0; j < M; ++j)
						d[i][j] = Math.min(d[i][j], ub + 1);
			}

//			System.out.print(" (b: " + lb + "/" + ub + ") ");

			/* If a bound is improved and the two bounds are not equal */
			boundImproved = (Math.abs(previousLB - lb) > 1E-4
					|| Math.abs(previousUB - ub) > 1E-4)
					&& Math.abs(ub - lb) > 1E-4;

			if(boundImproved)
				filterClientsAndFactories();

			iteration++;
		}
//		System.out.println("");

//		System.out.println("bounds: [" + previousLB + ", " + lb + "] [" + previousUB + " " + ub + "]");

		//		if(iteration > 2)
		//			System.out.print("!");



		this.cvg = new CplexVariableGetter(getCplex());
	}

	private void filterClientsAndFactories() {

		if(param.filterDominatedClientsAndFactories) {

			int dominatedClients = 0;
			int dominatedFactories = 0;

			for(int c1 = 0; c1 < N; ++c1) {

				if(!isClientDominated(c1))
					for(int c2 = c1+1; c2 < N; ++c2) {

						// Warning: Client c1 can become dominated in the c2 for loop
						if(!isClientDominated(c2) && !isClientDominated(c1)) {
							boolean c1LowerThanC2 = true;
							boolean c2LowerThanC1 = true;
							int j = 0;

							while((c1LowerThanC2 || c2LowerThanC1) && j < M) {

								if(!isFactoryDominated(j)) 
								{
									if(d[c1][j] > d[c2][j])
										c2LowerThanC1 = false;

									if(d[c1][j] > d[c2][j])
										c1LowerThanC2 = false;
								}
								j++;

							}

							if(c1LowerThanC2) {
								if(c1 > 0)
									d[c1][0] = Double.MAX_VALUE;
								else
									isFirstClientDominated = true;

								dominatedClients++;
							}

							else if(c2LowerThanC1 && !c1LowerThanC2) { 
								if(c2 > 0)
									d[c2][0] = Double.MAX_VALUE;
								else
									isFirstFactoryDominated = true;

								dominatedClients++;
							}
						}
					}
			}

			for(int f1 = 0; f1 < M; ++f1)
				if(!isFactoryDominated(f1))
					for(int f2 = f1+1; f2 < M; ++f2) {

						// Warning: Factory f1 can become dominated in the f2 for loop
						if(!isFactoryDominated(f2) && !isFactoryDominated(f1)) {

							boolean f1Dominates = true;
							boolean f2Dominates = true;
							int i = 0;

							while((f1Dominates || f2Dominates) && i < N) {

								if(!isClientDominated(i)) 
								{
									if(d[i][f1] > d[i][f2])
										f2Dominates = false;

									if(d[i][f2] > d[i][f1])
										f1Dominates = false;
								}

								i++;

							}

							if(f1Dominates) {

								//								System.out.println("is not dom: " + (d[0][f1] == Double.MAX_VALUE) + "/" + (d[0][f2] == Double.MAX_VALUE));

								//								System.out.println("F" + f1 + " dominated by F" + f2);
								//
								//								System.out.println("F" + f1);
								//								for(int t = 0; t < N; t++)
								//									System.out.print("\t" + d[t][f1]);
								//								System.out.println("\nF" + f2);
								//								for(int t = 0; t < N; t++)
								//									System.out.print("\t" + d[t][f2]);
								//								System.out.println();

								if(f1 > 0)
									d[0][f1] = Double.MAX_VALUE;
								else
									isFirstFactoryDominated = true;

								dominatedFactories++;

								//					System.out.println("++factory " + f1 + " dominated");
							}

							if(f2Dominates && !f1Dominates) {

								//								System.out.println("is not dom: " + (d[0][f1] == Double.MAX_VALUE) + "/" + (d[0][f2] == Double.MAX_VALUE));
								//
								//								System.out.println("F" + f2 + " dominated by F" + f1);
								//
								//								System.out.println("F" + f1);
								//								for(int t = 0; t < N; t++)
								//									System.out.print("\t" + d[t][f1]);
								//								System.out.println("\nF" + f2);
								//								for(int t = 0; t < N; t++)
								//									System.out.print("\t" + d[t][f2]);
								//								System.out.println();

								if(f2 > 0)
									d[0][f2] = Double.MAX_VALUE;
								else
									isFirstFactoryDominated = true;

								dominatedFactories++;
								//					System.out.println("++factory " + f2 + " dominated");
							}
						}
					}


						System.out.print(" (dom " + dominatedClients + "/" + dominatedFactories + ")" );


		}
	}

	public boolean isClientDominated(int idI) {return idI > 0 ? d[idI][0] == Double.MAX_VALUE: isFirstClientDominated;}
	public boolean isFactoryDominated(int idJ) {return idJ > 0 ? d[0][idJ] == Double.MAX_VALUE: isFirstFactoryDominated;}


	/**
	 * Compute the lower bounds:
	 * lb0 = max_i min_j d_ij
	 * lb1 = (M-p)th smallest value of (max_i min_{h != j} d_ih) for all j
	 */
	public double computeLowerBound() {

		/* Order for each client its distance to the factories */ 
		List<TreeSet<PositionedDistance>> orderedLines = new ArrayList<>();

		for(int i = 0 ; i < N; ++i) {
			TreeSet<PositionedDistance> tree = null;

			if(!isClientDominated(i)) {
				tree = new TreeSet<>(new Comparator<PositionedDistance>() {

					@Override
					public int compare(PCenter<CurrentParam>.PositionedDistance o1,
							PCenter<CurrentParam>.PositionedDistance o2) {
						int value = (int)(o1.distance - o2.distance);
						if(value == 0)
							value = 1;

						return value;
					}
				});

				for(int j = 0; j < M; ++j)
					if(!isFactoryDominated(j))
						tree.add(new PositionedDistance(j, d[i][j]));
			}

			orderedLines.add(tree);
		}

		double lb0 = -Double.MAX_VALUE;

		if(param.useLB0) {

			for(TreeSet<PositionedDistance> tree: orderedLines) {

				/* If the client is not dominated */
				if(tree != null) {
					Iterator<PositionedDistance> it = tree.iterator();
					double minValue = it.next().distance;

					if(minValue > lb0)
						lb0 = minValue;
				}

			}

			//						System.out.println("\nLB0: " + lb0);
		}

		double lb1 = -Double.MAX_VALUE;

		if(param.useLB1) {

			/* Tree set that will contain for each factory j, max_i min_{h != j} d[i][h]
			 * (i.e., lb0 if we know that factory j is not built) */
			TreeSet<Double> gamma = new TreeSet<>(new Comparator<Double>() {

				@Override
				public int compare(Double arg0, Double arg1) {
					int value = (int)(arg0 - arg1);
					if(value == 0)
						value = 1;
					return value;
				}

			});
			
//			System.out.println("\n---");

			/* For each factory */
			for(int j = 0 ; j < M; j++) {

				if(!isFactoryDominated(j)) {
					
//					System.out.print("j: " + j + " ");
					
					double gammaJ = -Double.MAX_VALUE;

					/* For each client */
					for(TreeSet<PositionedDistance> tree: orderedLines) {

//						System.out.print(tree + ", ");
						/* If the client is not dominated */
						if(tree != null) {
							Iterator<PositionedDistance> it = tree.iterator();
							PositionedDistance pd = it.next();

							if(pd.position == j)
								pd = it.next();

							double minValue = pd.distance;

							if(minValue > gammaJ)
								gammaJ = minValue;
						}

					}
					gamma.add(gammaJ);
				}

			}

//						System.out.println("\nList of the gammas: " + gamma);

			/* Browse <gamma> until position M-p */
			if(p < M - p) 
			{
				Iterator<Double> it = gamma.descendingIterator();

				for(int i = 0; i <= Math.min(p, gamma.size()); ++i)
					lb1 = it.next();
			}
			else {
				Iterator<Double> it = gamma.iterator();

				for(int i = 0; i  < Math.min(gamma.size() - p, gamma.size()); ++i)
					lb1 = it.next();
			}

			//			System.out.println("\nLB1: " + lb1);

		}

		return Math.max(lb, Math.max(lb0, lb1));
//		return Math.max(lb0, lb1);

	}	

	/**
	 * Compute the upper bounds:
	 * ub0 = min_j max_i d_ij
	 * ub1 = greedy solution (at each step add the factory which reduces the radius)
	 */
	public double computeUpperBound() {

		Random r = new Random();

		/* List of the factories that are not currently in the greedy solution */
		List<Integer> remainingFactories = new ArrayList<>();

		for(int j = 0; j < M; ++j)
			if(!isFactoryDominated(j))
				remainingFactories.add(j);

		/* Distance of each client to its closest factory currently in the solution */
		double[] distToFactory = new double[N];

		for(int i = 0; i < N; ++i) 
			distToFactory[i] = Double.MAX_VALUE;

		/* Value of the current solution */
		double currentRadius = Double.MAX_VALUE;

		int nbOfSteps = 1;

		if(param.useUB1)
			nbOfSteps = p;
//		System.out.print("!");

		/* For each step of the greedy algorithm */
		for(int step = 0; step < nbOfSteps; ++step) {

			/* Best factories currently found and their radius */
			List<Integer> bestCandidates = new ArrayList<>();
			double bestRadius = currentRadius;

//			System.out.println("Remaining factories: " + remainingFactories);

			/* For each remaining factory */
			for(Integer j: remainingFactories) {

//				System.out.print("\nj/d[0][j]: " + j + "/" + d[0][j]);
				/* New radius if j is added to the solution */
				Double radiusJ = null;

				for(int i = 0; i < N; ++i) 
					if(!isClientDominated(i)) // OK ?????????
						if(radiusJ == null)
							radiusJ = Math.min(distToFactory[i], d[i][j]);
						else
							radiusJ = Math.max(radiusJ, Math.min(distToFactory[i], d[i][j]));

//				System.out.println("radiusJ: " + radiusJ);
				
				if(radiusJ != null && radiusJ <= bestRadius) {

					if(radiusJ < bestRadius) {
						bestCandidates = new ArrayList<>();
						bestCandidates.add(j);
						bestRadius = radiusJ;
					}
					else 
						bestCandidates.add(j);

				}	
			}

//			System.out.println("Best candidates: " + bestCandidates);

			if(remainingFactories.size() == 0) {
				System.out.println("Error PCenter, remaining factories is empty");
				System.exit(0);
			}
			if(bestCandidates.size() == 0) {
				System.out.println("Error PCenter, remaining factories is empty");
				System.exit(0);
			}

			// Can bestCandidates be empty here ?
			int id = r.nextInt(bestCandidates.size());

			/* Update the distances to the factories */
			for(int i = 0; i < N; ++i) 
				if(!isClientDominated(i))
					distToFactory[i] = Math.min(distToFactory[i], d[i][bestCandidates.get(id)]);

			/* Update the radius */
			currentRadius = bestRadius;

			remainingFactories.remove(Integer.valueOf(bestCandidates.get(id)));


		}


		//		System.out.println("Upper bound: " + currentRadius + " (factories not used: " + remainingFactories + ")");

		return Math.min(ub, currentRadius);
//		return currentRadius;

	}

	public abstract double getRadius() throws IloException;

	private class PositionedDistance{
		int position;
		double distance;

		public PositionedDistance(int position, double distance) {
			this.position = position;
			this.distance = distance;
		}
		
		@Override
		public String toString() {return distance + "";}
	}

	public void createFormulation() throws IloException {

		if(!param.cplexOutput)
			getCplex().turnOffCPOutput();

		if(param.cplexAutoCuts)
			getCplex().removeAutomaticCuts();

		if(param.cplexPrimalDual)
			getCplex().turnOffPrimalDualReduction();

		/* Create the model */
		getCplex().iloCplex.clearModel();
		getCplex().iloCplex.clearCallbacks();

		/* Reinitialize the parameters to their default value */
		getCplex().setDefaults();

		if(param.tilim != -1)
			getCplex().setParam(IloCplex.DoubleParam.TiLim, Math.max(10,param.tilim));

		createFactoryVariables();
		createNoneFactoryVariables();
		createConstraints();
		createObjective();
	}

	private void createFactoryVariables() throws IloException {

		if(param.isInt && param.isYInt)
			y = new IloIntVar[M];
		else 
			y = new IloNumVar[M];

		for(int j = 0 ; j < M ; j++) {
			if(!isFactoryDominated(j)) {
				if(param.isInt && param.isYInt)
					y[j] = getCplex().iloCplex.intVar(0, 1);
				else
					y[j] = getCplex().iloCplex.numVar(0, 1);

				y[j].setName("y" + j);
			}
		}

	}

	protected abstract void createConstraints() throws IloException;

	protected abstract void createNoneFactoryVariables() throws IloException;

	protected abstract void createObjective() throws IloException;

	@Override
	public int n() {
		return N;
	}

	@Override
	public IloNumVar nodeVar(int i) throws IloException {
		return y[i];
	}

	@Override
	public Cplex getCplex() {
		return param.cplex;
	}

	@Override
	public CplexVariableGetter variableGetter() {
		return cvg;
	}

	@Override
	public void displaySolution() throws UnknownObjectException, IloException {
		displayYVariables(5);
	}

	protected void createAtMostPCenter() throws IloException {

		IloLinearNumExpr expr = getCplex().linearNumExpr();

		for(int m = 0 ; m < M ; m++)
			if(!isFactoryDominated(m))
				expr.addTerm(1.0, y[m]);

		getCplex().addRange(new Range(1.0, expr));
	}

	protected  void createAtLeastOneCenter() throws IloException {

		IloLinearNumExpr expr = getCplex().linearNumExpr();

		for(int m = 0 ; m < M ; m++)
			if(!isFactoryDominated(m)) {
				expr.addTerm(1.0, y[m]);
			}

		getCplex().addRange(new Range(expr, p));
	}

	/**
	 * Display the value of the y variables
	 * @param numberByLine Number of variable displayed on each line
	 * @throws IloException 
	 * @throws UnknownObjectException 
	 */
	protected void displayYVariables(int numberByLine) throws UnknownObjectException, IloException {

		NumberFormat nf = new DecimalFormat("#0.00");

		for(int i = 0 ; i < M ; i++) {
			if(!isFactoryDominated(i))
				System.out.print("y" + (i+1) + "=" + nf.format(cvg.getValue(y[i])) + "\t");
			else
				System.out.print("y" + (i+1) + "=" + "dominated" + "\t");

			if(i % numberByLine == numberByLine - 1)
				System.out.println();
		}
	}

	/**
	 * Generate an instance in which:
	 * - each client and each factory has (x,y) coordinates randomly generated between 0 and <maxCoordinateValue> 
	 * @param outputFile The path at which the output file will be created 
	 * @param seed The random seed
	 * @param n The number of clients
	 * @param m The number of factories
	 * @param maxCoordinateValue Maximal value of a coordinate of a factory
	 * @param factoriesEqualToClients True if the factories potential sites are the clients sites
	 */
	public static void generateInstance(String outputFile, int seed, int p, int n, int m, int maxCoordinateValue, boolean factoriesEqualToClients) {

		Random r = new Random(seed);

		/* Generate the client coordinates */
		int[][] clientCoordinates = new int[n][2];

		for(int i = 0 ; i < n ; i++) {
			clientCoordinates[i][0] = r.nextInt(maxCoordinateValue);
			clientCoordinates[i][1] = r.nextInt(maxCoordinateValue);
		}

		/* Generate the factories coordinates */
		int[][] factoriesCoordinates;

		if(factoriesEqualToClients) {
			factoriesCoordinates = clientCoordinates;
			m = n;
		}
		else {
			factoriesCoordinates = new int[m][2];
			for(int i = 0 ; i < m ; i++) {
				factoriesCoordinates[i][0] = r.nextInt(maxCoordinateValue);
				factoriesCoordinates[i][1] = r.nextInt(maxCoordinateValue);
			}
		}

		int[][] distances = new int[n][m];

		for(int i = 0 ; i < n ; i++)
			for(int j = 0 ; j < n ; j++)
				distances[i][j] = (int)Math.sqrt(Math.pow(clientCoordinates[i][0]-factoriesCoordinates[j][0], 2) + Math.pow(clientCoordinates[i][1]-factoriesCoordinates[j][1], 2));

		try{
			FileWriter fw = new FileWriter(outputFile, false); // True if the text is appened at the end of the file, false if the content of the file is removed prior to write in it
			BufferedWriter output = new BufferedWriter(fw);


			output.write(n + " " + m + " " + p + "\n");

			for(int i = 0 ; i < n ; i++) {
				for(int j = 0 ; j < m ; j++)
					output.write(distances[i][j] + " ");
				output.write("\n");
				output.flush();
			}

			output.close();
		}
		catch(IOException ioe){
			System.out.print("Erreur : ");
			ioe.printStackTrace();
		}



	}

	public abstract String getMethodName();


	public static PCResult solve(PCenter<?> formulation) throws IloException {

		double time = formulation.getCplex().getCplexTime();
		formulation.createFormulation();

		formulation.getCplex().solve();
		time = formulation.getCplex().getCplexTime() - time;

		//		System.out.println("\n---\n" + formulation.getMethodName());
		//		formulation.displaySolution();

		return new PCResult(formulation.getRadius(), time, formulation.getMethodName());
	}


	public static void batchSolve(String filePath, Cplex cplex) throws IloException, IOException, InvalidPCenterInputFile {

		NumberFormat nf = new DecimalFormat("#0.0"); 

		/* List of parameters */
		PCenterIndexedDistancesParam paramPCSCInt = new PCenterIndexedDistancesParam(filePath, cplex);
		paramPCSCInt.filterDominatedClientsAndFactories = true;
		//				paramPCSCInt.computeBoundsSeveralTimes = false;

		PCenterIndexedDistancesParam paramPCSCRelax = new PCenterIndexedDistancesParam(filePath, cplex);
		paramPCSCRelax.isInt = false;
		paramPCSCRelax.filterDominatedClientsAndFactories = true;
		//				paramPCSCRelax.computeBou ndsSeveralTimes = false;

		/* Resolution */
		List<PCResult> resultsOpt = new ArrayList<>();
		List<PCResult> resultsRelax = new ArrayList<>();

		/* PCSCO */
//		resultsOpt.add(PCenter.solve(new PCSCOrdered(paramPCSCInt)));
		//		//		resultsRelax.add(solve(new PCSCOrdered(paramPCSCRelax)));
		//
		/* PCRad */
//		resultsOpt.add(PCRadiusIndex.solveLBStarFirst(paramPCSCInt));
		//		//		resultsRelax.add(solve(new PCRadiusIndex(paramPCSCRelax)));
		//		//		resultsOpt.add(PCenter.solve(new PCRadiusIndex(paramPCSCInt)));



		//		/* PCSC */
		//		resultsRelax.add(solve(new PCSC(paramPCSCRelax)));
		//						resultsOpt.add(PCenter.solve(new PCSC(paramPCSCInt)));


		//		/* With iterative relaxations */
		//		//		paramPCSCInt.useLB0 = false;
		//		//		paramPCSCInt.useLB1 = false;
		//		//		paramPCSCInt.useBounds(false);
		resultsOpt.add(PCenter.solveIteratively(new PCSCCreator(), paramPCSCInt));
		resultsOpt.add(PCenter.solveIteratively(new PCSCOCreator(), paramPCSCInt));
		resultsOpt.add(PCenter.solveIteratively(new PCRadCreator(), paramPCSCInt));
		//		//		resultsOpt.add(PCenter.solveIteratively(new PCRadCreator(), new PCSCOCreator(), paramPCSCInt));




		/* 1 - Display the relaxations */
		if(resultsRelax.size() > 0) {
			System.out.print("\n\tRelaxation ");

			for(PCResult res: resultsRelax)
				System.out.print(res.methodName + "/");
			System.out.print(": ");

			for(PCResult res: resultsRelax)
				System.out.print(nf.format(res.radius) + "/");
		}

		/* 2 - Display the resolution times (and optionally the radiuses if there are differences) */
		System.out.println("\n\tTime ");

		Collections.sort(resultsOpt, new Comparator<PCResult>() {

			@Override
			public int compare(PCResult o1, PCResult o2) {
				return (int)(1000 * (o1.time - o2.time));
			}

		});

		for(PCResult res: resultsOpt)
			System.out.println("\t\t" + res.methodName + ": " + nf.format(res.time) + "s");

		String optRadiuses = "";
		double optRadius = -Double.MAX_VALUE;

		for(PCResult res: resultsOpt) {
			//			System.out.print(nf.format(res.time) + "/");

			optRadiuses += res.radius + "/";

			/* If it is the first method */
			if(optRadius == -Double.MAX_VALUE)
				optRadius = res.radius;

			/* If the results are not coherent with the previous methods */
			else if(Math.abs(optRadius - res.radius) > 1E-4)
				optRadius = Double.MAX_VALUE;

		}


		if(optRadius == Double.MAX_VALUE) {
			System.out.println("\n!!! Different optimum solutions obtained: " + optRadiuses);
			System.exit(0);
		}
		else		
			System.out.println("\n\tOptimal radius: " + nf.format(optRadius) + "\n");



	}



	public static PCResult solveIteratively(PCenterCreator creatorRelaxation, PCenterCreator creatorIntegerResolution, PCenterIndexedDistancesParam param) throws IloException, IOException, InvalidPCenterInputFile {

		param = new PCenterIndexedDistancesParam(param);
		param.isInt = false;

		NumberFormat nf = new DecimalFormat("#0.0");

		double time = param.cplex.getCplexTime();

		double timeCreation = 0;

		double lastRelaxation = Double.MAX_VALUE;
		double previousRelaxation = -Double.MAX_VALUE;

		System.out.print("\tRelax " + creatorRelaxation.getMethodName() + ": ");
		while(Math.abs(lastRelaxation - previousRelaxation) > 1E-4) {

			timeCreation -= param.cplex.getCplexTime();
			PCenter<?> relax = creatorRelaxation.createFormulationObject(param);
			relax.createFormulation();
			timeCreation += param.cplex.getCplexTime();
			relax.getCplex().solve();
			double radius = relax.getRadius();

			System.out.print(nf.format(radius) + " ");

			previousRelaxation = lastRelaxation;
			lastRelaxation = radius;

			param.initialLB = (int)Math.ceil(radius);
			param.initialUB = relax.ub;

			// Does not improve the results
			//			param.initialLB = relax.lowestDistanceGreaterThan(radius);

		}

		System.out.println( "(" + nf.format(timeCreation) + "/" + nf.format((param.cplex.getCplexTime() - time)) + " s)");

		param.isInt = true;

		timeCreation -= param.cplex.getCplexTime();
		PCenter<?> pcsc = creatorIntegerResolution.createFormulationObject(param);
		pcsc.createFormulation();
		timeCreation += param.cplex.getCplexTime();

		//		System.out.println("\tK: " + ((PCDistanceOrdered)pcsc).K);

		pcsc.getCplex().solve();


		//		System.out.println("\n---\n" + pcsc.getMethodName());
		//		pcsc.displaySolution();
		time = pcsc.getCplex().getCplexTime() - time;

		String method = creatorRelaxation.getMethodName();

		if(!creatorRelaxation.getMethodName().equals(creatorIntegerResolution.getMethodName()))
			method += "_" + creatorIntegerResolution.getMethodName();

//		pcsc.displaySolution();
		return new PCResult(pcsc.getRadius(), time, method.toLowerCase() + "_it");


	}

	public static PCResult solveIteratively(PCenterCreator creator, PCenterIndexedDistancesParam param) throws IloException, IOException, InvalidPCenterInputFile {
		return solveIteratively(creator, creator, param);	
	}

	//	public static void main(String[] args) {
	//
	//		for(int i = 200 ; i < 210 ; i+= 10)
	//			for(int p = 2 ; p < 10 ; p++)
	//				PCenter.generateInstance("data/pcenters/random/pc_n"+i+"_p"+p+"_i"+"_1.dat", p, p, i, i, 1000, true);
	//
	//	}
}
