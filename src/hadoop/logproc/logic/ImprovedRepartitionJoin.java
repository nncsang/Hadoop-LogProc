package hadoop.logproc.logic;

import hadoop.logproc.data.TextPair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;


/**
 * 
 * This file implements Improved Repartition Join from the paper "A Comparison of Join Algorithms for Log Processing in MapReduce"
 * 
 * Author: NGUYEN Ngoc Chau Sang
 */

public class ImprovedRepartitionJoin extends Configured implements Tool{
	private int numReducers;
	private Path refFile;
	private Path logFile;
	private Path outputDir;
	
	public ImprovedRepartitionJoin(String[] args) {
	    if (args.length != 4) {
	      System.out.print(args.length);
	      System.out.println("Usage: WordCount <num_reducers> <input_ref_path> <input_log_path> <output_path>");
	      System.exit(0);
	    }
	    
	    this.numReducers = Integer.parseInt(args[0]);
	    this.refFile = new Path(args[1]);
	    this.logFile = new Path(args[2]);
	    this.outputDir = new Path(args[3]);
	}
	  
	@Override
	public int run(String[] arg0) throws Exception {
		Configuration conf = this.getConf();
	    
	    /**	If file output is existed, delete it
	     * 	Remove these line of codes from a real MapReduce application
	     */
	    FileSystem fs = FileSystem.get(conf);
	    if(fs.exists(outputDir)){
	       fs.delete(outputDir, true);
	    }
	    
	    // Define new job
	    Job job = new Job(conf, "ImprovedRepartitionJoin"); //define new job
	    
	    
	    // Set job output format
	    job.setOutputFormatClass(TextOutputFormat.class);
	    
	    // Add the input files 
	    MultipleInputs.addInputPath(job, refFile, TextInputFormat.class, ImprovedRepartitionJoinRefMapper.class);
	    MultipleInputs.addInputPath(job, logFile, TextInputFormat.class, ImprovedRepartitionJoinLogMapper.class);
	    
	    
	    // Set the output path
	    FileOutputFormat.setOutputPath(job, outputDir);
	    
	    // Set reduce class and the reduce output key and value classes
	    job.setReducerClass(ImprovedRepartitionJoinReducer.class);
	    job.setOutputKeyClass(Text.class);
	    job.setOutputValueClass(Text.class);
	    job.setPartitionerClass(ImprovedRepartitionJoinPartioner.class);
	    job.setSortComparatorClass(ValueComparator.class);
	    
	    // Set map class and the map output key and value classes
	    job.setMapOutputKeyClass(Text.class);
	    job.setMapOutputValueClass(TextPair.class);
	    
	    // Set the number of reducers using variable numberReducers
	    job.setNumReduceTasks(numReducers);
	    
	    // Set the jar class
	    job.setJarByClass(ImprovedRepartitionJoin.class);
	   
	    return job.waitForCompletion(true) ? 0 : 1; // this will execute the job
	}
	
	public static void main(String args[]) throws Exception {
	    int res = ToolRunner.run(new Configuration(), new ImprovedRepartitionJoin(args), args);
	    System.exit(res);
	}
	
	static class ValueComparator extends WritableComparator implements RawComparator {

		protected ValueComparator(Class<TextPair> keyClass) {
			super(TextPair.class);
			// TODO Auto-generated constructor stub
		} 
		
		@Override
		public int compare(WritableComparable a, WritableComparable b) {
			int type1 = Integer.parseInt(((TextPair) a).getFirst().toString());
			int type2 = Integer.parseInt(((TextPair) b).getFirst().toString());
			
			if (type1 > type2)
				return 1;
			if (type1 == type2)
				return 0;
			return -1;
		}
	}
}

class ImprovedRepartitionJoinPartioner extends Partitioner<TextPair, TextPair>{
	 @Override
	    public int getPartition(TextPair key, TextPair value,
	        int numPartitions) {
	    	return (key.getFirst().hashCode()) % numPartitions;
	    }
}

class ImprovedRepartitionJoinRefMapper extends Mapper<LongWritable, 
								Text, 
								TextPair, 
								TextPair> { 
	private Text zero = new Text("0");
	@Override
	protected void map(LongWritable key, 
						Text value, 
						Context context) throws IOException, InterruptedException {
		
		String[] values = value.toString().split("\t");
		context.write(new TextPair(new Text(values[0]), zero) , new TextPair(new Text("0"), new Text(values[1])));
	}
}

class ImprovedRepartitionJoinLogMapper extends Mapper<LongWritable, 
														Text, 
														TextPair, 
														TextPair> { 
	private Text one = new Text("1");

	@Override
	protected void map(LongWritable key, 
						Text value, 
						Context context) throws IOException, InterruptedException {
		String[] values = value.toString().split("\t");
		context.write(new TextPair(new Text(values[0]), one), new TextPair(new Text("1"), new Text(values[1] + " - " + values[2])));
	}
}

class ImprovedRepartitionJoinReducer extends Reducer<TextPair, 
											TextPair, 
  											Text, 
  											Text> { 

	@Override
	protected void reduce(TextPair key, 
							Iterable<TextPair> values, 
							Context context) throws IOException, InterruptedException {  
		Iterator<TextPair> iter = values.iterator();
		List<String> ref = new ArrayList<String>();
		List<String> log = new ArrayList<String>();
		
		TextPair value;
		boolean isLog = false;
		while (iter.hasNext()){
			value = iter.next();
			if (isLog == false && Integer.parseInt(value.getFirst().toString()) == 0){
				ref.add(value.getSecond().toString());
			}else{
				isLog = true;
				log.add(value.getSecond().toString());
			}
		}
		
		// For both one-to-one, one-to-many, many-to-many join 
		for(int i = 0; i < ref.size(); i++)
			for(int j = 0; j < log.size(); j++)
				context.write(new Text(log.get(j)), new Text(ref.get(i)));
	}
}



