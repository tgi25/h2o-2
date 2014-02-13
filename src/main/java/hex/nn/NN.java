package hex.nn;

import hex.FrameTask;
import hex.FrameTask.DataInfo;
import water.*;
import water.api.DocGen;
import water.api.NNProgressPage;
import water.api.RequestServer;
import water.fvec.Frame;
import water.util.Log;
import water.util.RString;

import java.util.Random;

import static water.util.MRUtils.sampleFrame;

public class NN extends Job.ValidatedJob {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  public static DocGen.FieldDoc[] DOC_FIELDS;
  public static final String DOC_GET = "Neural Network";

  public DataInfo _dinfo;
  private boolean _gen_enum;

  @API(help = "Activation function", filter = Default.class, json = true)
  public Activation activation = Activation.Tanh;

  @API(help = "Input layer dropout ratio", filter = Default.class, dmin = 0, dmax = 1, json = true)
  public double input_dropout_ratio = 0.2;

  @API(help = "Hidden layer sizes, e.g. 1000, 1000. Grid search: (100, 100), (200, 200)", filter = Default.class, json = true)
  public int[] hidden = new int[] { 200, 200 };

  @API(help = "Learning rate (higher => less stable, lower => slower convergence)", filter = Default.class, dmin = 0, dmax = 1, json = true)
  public double rate = .005;

  @API(help = "Learning rate annealing: rate / (1 + rate_annealing * samples)", filter = Default.class, dmin = 0, dmax = 1, json = true)
  public double rate_annealing = 1 / 1e6;

  @API(help = "L1 regularization, can add stability", filter = Default.class, dmin = 0, dmax = 1, json = true)
  public double l1 = 0.0;

  @API(help = "L2 regularization, can add stability", filter = Default.class, dmin = 0, dmax = 1, json = true)
  public double l2 = 0.0;

  @API(help = "Initial momentum at the beginning of training", filter = Default.class, dmin = 0, dmax = 0.9999999999, json = true)
  public double momentum_start = .5;

  @API(help = "Number of training samples for which momentum increases", filter = Default.class, lmin = 0, json = true)
  public long momentum_ramp = 1000000;

  @API(help = "Final momentum after the ramp is over", filter = Default.class, dmin = 0, dmax = 0.9999999999, json = true)
  public double momentum_stable = 0.99;

  @API(help = "How many times the dataset should be iterated (streamed), can be less than 1.0", filter = Default.class, dmin = 0, json = true)
  public double epochs = 10;

  @API(help = "Seed for random numbers (reproducible results for single-threaded only, cf. Hogwild)", filter = Default.class, json = true)
  public long seed = new Random().nextLong();

  @API(help = "Enable expert mode", filter = Default.class, json = true)
  public boolean expert_mode = false;

  @API(help = "Initial Weight Distribution", filter = Default.class, json = true)
  public InitialWeightDistribution initial_weight_distribution = InitialWeightDistribution.UniformAdaptive;

  @API(help = "Uniform: -value...value, Normal: stddev)", filter = Default.class, dmin = 0, json = true)
  public double initial_weight_scale = 1.0;

  @API(help = "Loss function", filter = Default.class, json = true)
  public Loss loss = Loss.CrossEntropy;

  @API(help = "Learning rate decay factor between layers (N-th layer: rate*alpha^(N-1))", filter = Default.class, dmin = 0, json = true)
  public double rate_decay = 1.0;

  @API(help = "Constraint for squared sum of incoming weights per unit", filter = Default.class, json = true)
  public double max_w2 = Double.POSITIVE_INFINITY;

  @API(help = "Number of training set samples for scoring (0 for all)", filter = Default.class, lmin = 0, json = true)
  public long score_training_samples = 10000l;

  @API(help = "Number of validation set samples for scoring (0 for all)", filter = Default.class, lmin = 0, json = true)
  public long score_validation_samples = 0l;

  @API(help = "Shortest interval (in seconds) between scoring", filter = Default.class, dmin = 0, json = true)
  public double score_interval = 2;

  @API(help = "Number of training set samples between synchronization (0 for all).", filter = Default.class, lmin = 0, json = true)
  public long sync_samples = 1000l;

  @API(help = "Enable diagnostics for hidden layers", filter = Default.class, json = true)
  public boolean diagnostics = true;

  @API(help = "Enable fast mode (minor approximation in backpropagation)", filter = Default.class, json = true)
  public boolean fast_mode = true;

  public enum InitialWeightDistribution {
    UniformAdaptive, Uniform, Normal
  }

  /**
   * Activation functions
   * Tanh, Rectifier and RectifierWithDropout have been tested.  TanhWithDropout and Maxout are experimental.
   */
  public enum Activation {
    Tanh, TanhWithDropout, Rectifier, RectifierWithDropout, Maxout
  }

  /**
   * Loss functions
   * CrossEntropy is recommended
   */
  public enum Loss {
    MeanSquare, CrossEntropy
  }

  @Override
  protected void registered(RequestServer.API_VERSION ver) {
    super.registered(ver);
    for (Argument arg : _arguments) {
      if ( arg._name.equals("activation") || arg._name.equals("initial_weight_distribution") || arg._name.equals("expert_mode")) {
        arg.setRefreshOnChange();
      }
    }
  }

  @Override protected void queryArgumentValueSet(Argument arg, java.util.Properties inputArgs) {
    super.queryArgumentValueSet(arg, inputArgs);
    if (arg._name.equals("classification")) {
      classification = true;
      arg.disable("Regression is not currently supported.");
    }
    if (arg._name.equals("input_dropout_ratio") &&
            (activation != Activation.RectifierWithDropout && activation != Activation.TanhWithDropout)
            ) {
      arg.disable("Only with Dropout.", inputArgs);
    }
    if(arg._name.equals("initial_weight_scale") &&
            (initial_weight_distribution == InitialWeightDistribution.UniformAdaptive)
            ) {
      arg.disable("Using sqrt(6 / (# units + # units of previous layer)) for Uniform distribution.", inputArgs);
    }
    if(arg._name.equals("loss") && !classification) {
      arg.disable("Using MeanSquare loss for regression.", inputArgs);
      loss = Loss.MeanSquare;
    }
    if (arg._name.equals("score_validation_samples") && validation == null) {
      arg.disable("Only if a validation set is specified.");
    }
    if (arg._name.equals("sync_samples") && H2O.CLOUD.size() == 1) {
      sync_samples = 0; //sync once per epoch on a single node
      arg.disable("Only for multi-node operation.");
    }
    if (arg._name.equals("loss") || arg._name.equals("max_w2") || arg._name.equals("warmup_samples")
            || arg._name.equals("score_training_samples") || arg._name.equals("score_validation_samples")
            || arg._name.equals("initial_weight_distribution") || arg._name.equals("initial_weight_scale")
            || arg._name.equals("score_interval") || arg._name.equals("diagnostics")
            || arg._name.equals("rate_decay") || arg._name.equals("sync_samples")
            || arg._name.equals("fast_mode")
            ) {
      if (!expert_mode) arg.disable("Only in expert mode.");
    }
  }

  public Frame score( Frame fr ) { return ((NNModel)UKV.get(dest())).score(fr);  }

  /** Print model parameters as JSON */
  @Override public boolean toHTML(StringBuilder sb) {
    return makeJsonBox(sb);
  }

  /** Return the query link to this page */
  public static String link(Key k, String content) {
    RString rs = new RString("<a href='NN.query?source=%$key'>%content</a>");
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  @Override public float progress(){
    if(DKV.get(dest()) == null)return 0;
    NNModel m = DKV.get(dest()).get();
    return (float)(m.epoch_counter / m.model_info().get_params().epochs);
  }

  @Override public Status exec() {
    initModel();
    buildModel();
    return Status.Done;
  }

  @Override protected Response redirect() {
    return NNProgressPage.redirect(this, self(), dest());
  }

  void checkParams() {
    if(!classification && loss != Loss.MeanSquare) {
      Log.warn("Setting loss to MeanSquare for regression.");
      loss = Loss.MeanSquare;
    }
    if (H2O.CLOUD.size() == 1 && sync_samples != 0) {
      Log.warn("Setting sync_samples to 0 for single-node operation.");
      sync_samples = 0;
    }
    // make default job_key and destination_key in case they are missing
    if (dest() == null) destination_key = Key.make("NN_model");
    if (self() == null) {
      job_key = Key.make("NN_job");
      DKV.put(self(), new Value(self(), new byte[0]), null);
    }
  }

  public void initModel() {
    checkParams();
    logStart();

    // Lock the input datasets against deletes
    source.read_lock(self());
    if( validation != null && source._key != null && validation._key !=null && !source._key.equals(validation._key) )
      validation.read_lock(self());

    if (_dinfo == null)
      _dinfo = new FrameTask.DataInfo(FrameTask.DataInfo.prepareFrame(source, response, ignored_cols, true), 1, true);
    NNModel model = new NNModel(dest(), self(), source._key, _dinfo, this);
    model.delete_and_lock(self());
    model.model_info().initializeMembers();
    final long[] model_size = model.model_info().size();
    Log.info("Number of model parameters (weights/biases): " + String.format("%g", (double)model_size[0]));
    Log.info("Memory usage of the model: " + String.format("%g", (double)model_size[1] / (1<<20)) + " MB.");
  }

  public NNModel buildModel() {
    final NNModel model = UKV.get(dest());
    final Frame[] adapted = validation == null ? null : model.adapt(validation, false);
    Frame train = _dinfo._adaptedFrame;
    Frame valid = validation == null ? null : adapted[0];

    // Optionally downsample data for scoring
    Frame trainScoreFrame = sampleFrame(train, score_training_samples, seed);
    Frame validScoreFrame = sampleFrame(valid, score_validation_samples, seed+1);

    if (sync_samples > train.numRows()) {
      Log.warn("Setting sync_samples (" + sync_samples
              + ") to the number of rows of the training data (" + (sync_samples=train.numRows()) + ").");
    }
    // determines the number of rows processed during NNTask, affects synchronization (happens at the end of each NNTask)
    final float sync_fraction = sync_samples == 0l ? 1.0f : (float)sync_samples / train.numRows();

    Log.info("Starting to train the Neural Net.");
    long timeStart = System.currentTimeMillis();
    //main loop
    do {
      // NNTask trains an internal deep copy of model_info
      NNTask nntask = new NNTask(_dinfo, model.model_info(), true, sync_fraction).doAll(train);
//      // FOR DEBUGGING ONLY
//      {
//        AutoBuffer bb = new AutoBuffer();
//        nntask.write(bb);
//        Log.info("Size of the (serialized) NNTask: " + String.format("%.3f", (double) bb.buf().length / (1 << 20)) + " MB.");
//      }
      //need this for next iteration
      model.set_model_info(nntask.model_info());
    } while (model.doDiagnostics(trainScoreFrame, validScoreFrame, timeStart, self())); //diagnostics, msgs, UKV

    //cleanup
    if (adapted != null) adapted[1].delete();
    model.unlock(self());
    if (validScoreFrame != null && validScoreFrame != adapted[0]) validScoreFrame.delete();
    if (trainScoreFrame != null && trainScoreFrame != train) trainScoreFrame.delete();
    if( _gen_enum ) UKV.remove(response._key);
    UKV.remove(self());
    Log.info("Neural Net training finished.");
    return model;
  }

}